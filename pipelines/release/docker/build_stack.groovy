node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    config = util.scipipeConfig()
  }
}

notify.wrap {
  util.requireParams([
    'NO_PUSH',
    'PRODUCT',
    'TAG',
    'TIMEOUT',
  ])

  String product    = params.PRODUCT
  String eupsTag    = params.TAG
  Boolean noPush    = params.NO_PUSH
  Integer timelimit = params.TIMEOUT

  def scipipe        = config.scipipe_release
  def dockerfile     = scipipe.dockerfile
  def dockerRegistry = scipipe.docker_registry
  def newinstall     = config.newinstall

  def githubRepo     = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef         = dockerfile.git_ref
  def buildDir       = dockerfile.dir
  def dockerRepo     = dockerRegistry.repo
  def dockerTag      = "7-stack-lsst_distrib-${eupsTag}"
  def timestamp      = util.epochMilliToUtc(currentBuild.startTimeInMillis)
  def shebangtronUrl = util.shebangtronUrl()

  def newinstallImage = newinstall.docker_registry.repo
  newinstallImage += ":${newinstall.docker_registry.tag}"
  def baseImage = newinstallImage

  def image = null
  def repo  = null

  def run = {
    stage('checkout') {
      repo = git([
        url: githubRepo,
        branch: gitRef,
      ])
    }

    stage('build') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg EUPS_PRODUCT=\"${product}\""
      opt << "--build-arg EUPS_TAG=\"${tag}\""
      opt << "--build-arg DOCKERFILE_GIT_BRANCH=\"${repo.GIT_BRANCH}\""
      opt << "--build-arg DOCKERFILE_GIT_COMMIT=\"${repo.GIT_COMMIT}\""
      opt << "--build-arg DOCKERFILE_GIT_URL=\"${repo.GIT_URL}\""
      opt << "--build-arg JENKINS_JOB_NAME=\"${env.JOB_NAME}\""
      opt << "--build-arg JENKINS_BUILD_ID=\"${env.BUILD_ID}\""
      opt << "--build-arg JENKINS_BUILD_URL=\"${env.RUN_DISPLAY_URL}\""
      opt << "--build-arg BASE_IMAGE=\"${baseImage}\""
      opt << "--build-arg SHEBANGTRON_URL=\"${shebangtronUrl}\""
      opt << '.'

      dir(buildDir) {
        image = docker.build("${dockerRepo}", opt.join(' '))
      }
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          [dockerTag, "${dockerTag}-${timestamp}"].each { name ->
            image.push(name)
          }
        }
      }
    } // push
  } // run

  node('docker') {
    try {
      timeout(time: timelimit, unit: 'HOURS') {
        run()
      }
    } finally {
      stage('archive') {
        def resultsFile = 'results.json'

        util.dumpJson(resultsFile,  [
          base_image: baseImage ?: null,
          image: "${dockerRepo}:${dockerTag}",
          docker_registry: [
            repo: dockerRepo,
            tag: dockerTag
          ],
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      } // stage
    } // try
  } // node
} // notify.wrap
