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
  }
}

notify.wrap {
  def product         = 'qserv_distrib'
  def tarballProducts = product
  def retries         = 3
  def eupsTag         = 'qserv-dev'

  def manifestId = null

  def run = {
    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            PRODUCT: product,
            SKIP_DEMO: true,
            SKIP_DOCS: true,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      retry(retries) {
        util.runPublish(
          parameters: [
            EUPSPKG_SOURCE: 'git',
            MANIFEST_ID: manifestId,
            EUPS_TAG: eupsTag,
            PRODUCT: product,
          ],
        )
      } // retry
    } // stage
  } // run

  try {
    timeout(time: 30, unit: 'HOURS') {
      run()
    }
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        util.dumpJson(resultsFile, [
          manifest_id: manifestId ?: null,
          eups_tag: eupsTag ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
