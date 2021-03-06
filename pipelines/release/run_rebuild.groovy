def config = null

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
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'BRANCH',
    'PREP_ONLY',
    'PRODUCT',
    'SKIP_DEMO',
    'SKIP_DOCS',
    'TIMEOUT',
  ])

  String branch     = params.BRANCH
  Boolean prepOnly  = params.PREP_ONLY
  String product    = params.PRODUCT
  Boolean skipDemo  = params.SKIP_DEMO
  Boolean skipDocs  = params.SKIP_DOCS
  Integer timelimit = params.TIMEOUT

  // not a normally exposed job param
  Boolean versiondbPush = (! params.NO_VERSIONDB_PUSH?.toBoolean())
  // default to safe
  def versiondbRepo = util.githubSlugToUrl(
    config.versiondb.github_repo,
    'https'
  )
  if (versiondbPush) {
    versiondbRepo = util.githubSlugToUrl(config.versiondb.github_repo, 'ssh')
  }

  def canonical    = config.canonical
  def lsstswConfig = canonical.lsstsw_config

  def slug = util.lsstswConfigSlug(lsstswConfig)

  def run = {
    ws(canonical.workspace) {
      def cwd = pwd()

      def buildParams = [
        EUPS_PKGROOT:       "${cwd}/distrib",
        VERSIONDB_REPO:      versiondbRepo,
        VERSIONDB_PUSH:      versiondbPush,
        GIT_SSH_COMMAND:     'ssh -o StrictHostKeyChecking=no',
        LSST_JUNIT_PREFIX:   slug,
        LSST_PYTHON_VERSION: lsstswConfig.python,
        LSST_COMPILER:       lsstswConfig.compiler,
        // XXX this should be renamed in lsstsw to make it clear that its
        // setting a github repo slug
        REPOSFILE_REPO:      config.repos.github_repo,
        BRANCH:              branch,
        PRODUCT:             product,
        SKIP_DEMO:           skipDemo,
        SKIP_DOCS:           skipDocs,
        PREP_ONLY:           prepOnly,
      ]

      def runJW = {
        // note that util.jenkinsWrapper() clones the ci-scripts repo, which is
        // used by the push docs stage
        try {
          util.jenkinsWrapper(buildParams)
        } finally {
          util.jenkinsWrapperPost()
        }
      }

      def withVersiondbCredentials = { invoke ->
        sshagent (credentials: ['github-jenkins-versiondb']) {
          invoke()
        }
      }

      stage('build') {
        util.insideDockerWrap(
          image: lsstswConfig.image,
          pull: true,
        ) {
          // only setup sshagent if we are going to push
          if (versiondbPush) {
            withVersiondbCredentials(runJW)
          } else {
            runJW()
          }
        } // util.insideDockerWrap
      } // stage('build')

      stage('push docs') {
        if (!skipDocs) {
          withCredentials([[
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'aws-doxygen-push',
            usernameVariable: 'AWS_ACCESS_KEY_ID',
            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
          ],
          [
            $class: 'StringBinding',
            credentialsId: 'doxygen-push-bucket',
            variable: 'DOXYGEN_S3_BUCKET'
          ]]) {
            withEnv([
              "EUPS_PKGROOT=${cwd}/distrib",
              "HOME=${cwd}/home",
            ]) {
              // the current iteration of the awscli container is alpine based
              // and doesn't work with util.insideDockerWrap.  However, the aws
              // cli seems to work OK without trying to lookup the username.
              docker.image(util.defaultAwscliImage()).inside {
                // alpine does not include bash by default
                util.posixSh '''
                  # provides DOC_PUSH_PATH
                  . ./ci-scripts/settings.cfg.sh

                  aws s3 cp \
                    --only-show-errors \
                    --recursive \
                    "${DOC_PUSH_PATH}/" \
                    "s3://${DOXYGEN_S3_BUCKET}/stack/doxygen/"
                '''
              } // util.insideDockerWrap
            } // withEnv
          } // withCredentials
        }
      } // stage('push docs')
    } // ws
  } // run

  node(lsstswConfig.label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
