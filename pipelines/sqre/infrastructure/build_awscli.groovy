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
    sqre = util.readYamlFile 'etc/sqre/config.yaml'
  }
}

notify.wrap {
  util.requireParams([
    'AWSCLI_VER',
    'LATEST',
    'NO_PUSH',
  ])

  String ver         = params.AWSCLI_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def awscli     = sqre.awscli
  def dockerfile = awscli.dockerfile
  def docker     = awscli.docker

  def githubRepo = util.githubSlugToUrl(dockerfile.github_repo, 'https')
  def githubRef  = dockerfile.git_ref
  def dockerRepo = docker.repo

  def image = null

  def run = {
    stage('checkout') {
      git([
        url: githubRepo,
        branch: gitRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      image = docker.build("${dockerRepo}", "--pull=true --no-cache --build-arg AWSCLI_VER=${ver} .")
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(ver)
          if (pushLatest) {
            image.push('latest')
          }
        }
      }
    } // push
  } // run

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // node
} // notify.wrap
