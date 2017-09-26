def notify = null

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
  }
}

try {
  notify.started()

  def retries = 3

  def requiredParams = [
    'PRODUCT',
    'EUPS_TAG',
  ]

  requiredParams.each { it ->
    if (!params.get(it)) {
      error "${it} parameter is required"
    }
  }

  timeout(time: 30, unit: 'HOURS') {
    stage("build eups tarballs") {
      def operatingsystem = [
        'centos-7',
        'centos-6',
        'osx-10.11',
      ]

      def pyenv = [
        new MinicondaEnv('2', '4.2.12', '7c8e67'), // keep until v14_0
        new MinicondaEnv('3', '4.2.12', '7c8e67'), // keep until v14_0
        new MinicondaEnv('2', '4.3.21', '10a4fa6'),
        new MinicondaEnv('3', '4.3.21', '10a4fa6'),
      ]

      def platform = [:]

      operatingsystem.each { os ->
        pyenv.each { py ->
          platform["${os}.${py.slug()}"] = {
            retry(retries) {
              timeout(time: 6, unit: 'HOURS') {
                build job: 'release/tarball',
                  parameters: [
                    string(name: 'PRODUCT', value: params.PRODUCT),
                    string(name: 'EUPS_TAG', value: params.EUPS_TAG),
                    booleanParam(name: 'SMOKE', value: params.SMOKE),
                    booleanParam(name: 'RUN_DEMO', value: params.RUN_DEMO),
                    booleanParam(name: 'PUBLISH', value: params.PUBLISH),
                    booleanParam(name: 'WIPEOUT', value: params.WIPEOUT),
                    string(name: 'PYVER', value: py.pythonVersion),
                    string(name: 'MINIVER', value: py.minicondaVersion),
                    string(name: 'LSSTSW_REF', value: py.lsstswRef),
                    string(name: 'OS', value: os),
                  ]
              }
            }
          }
        }
      }

      parallel platform
    }
  }
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}

/**
 * Represents a miniconda build environment.
 */
class MinicondaEnv implements Serializable {
  String pythonVersion
  String minicondaVersion
  String lsstswRef

  /**
   * Constructor.
   *
   * @param p Python major version number. Eg., '3'
   * @param m Miniconda version string. Eg., '4.2.12'
   * @param l {@code lsst/lsstsw} git ref.
   * @return MinicondaEnv
   */
  // unfortunately, a constructor is required under the security sandbox
  // See: https://issues.jenkins-ci.org/browse/JENKINS-34741
  MinicondaEnv(String p, String m, String l) {
    this.pythonVersion = p
    this.minicondaVersion = m
    this.lsstswRef = l
  }

  /**
   * Generate a single string description of miniconda env.
   */
  String slug() {
    "miniconda${pythonVersion}-${minicondaVersion}-${lsstswRef}"
  }
}
