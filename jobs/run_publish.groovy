import util.Common
Common.makeFolders(this)

// note that this job *will not work* unless run-rebuild has been executed at
// least once in order to initialize the env.
def j = job('release/run-publish') {
  parameters {
    choiceParam('EUPSPKG_SOURCE', ['git', 'package'])
    stringParam('BUILD_ID', null, 'BUILD_ID generated by lsst_build to generate EUPS distrib packages from. Eg. b1935')
    stringParam('TAG', null, 'EUPS distrib tag name to publish. Eg. w_2016_08')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to tag.')
  }

  label('lsst-dev')
  concurrentBuild(false)
  customWorkspace('/home/lsstsw/jenkins/release')

  environmentVariables {
    env('EUPS_PKGROOT', '/lsst/distserver/production')
    env('VERSIONDB_REPO', 'git@github.com:lsst/versiondb.git')
    env('VERSIONDB_PUSH', 'true')
  }

  wrappers {
    colorizeOutput('gnome-terminal')
    credentialsBinding {
      usernamePassword(
        'AWS_ACCESS_KEY_ID',
        'AWS_SECRET_ACCESS_KEY',
        'aws-eups-push'
      )
      string('EUPS_S3_BUCKET', 'eups-push-bucket')
    }
  }

  steps {
    shell(
      '''
      #!/bin/bash -e

      # ensure that we are using the lsstsw clone relative to the workspace
      # and that another value for LSSTSW isn't leaking in from the env
      export LSSTSW="${WORKSPACE}/lsstsw"

      # isolate eups cache files
      export EUPS_USERDATA="${WORKSPACE}/.eups"

      ARGS=()
      ARGS+=('-b' "$BUILD_ID")
      ARGS+=('-t' "$TAG")
      # split whitespace separated EUPS products into separate array elements
      # by not quoting
      ARGS+=($PRODUCT)

      export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"

      # setup.sh will unset $PRODUCTS
      source ./lsstsw/bin/setup.sh

      publish "${ARGS[@]}"
      '''.replaceFirst("\n","").stripIndent()

    )
    shell(
      '''
      #!/bin/bash -e

      # setup python env
      . "${WORKSPACE}/lsstsw/bin/setup.sh"

      mkdir -p publish
      pip install virtualenv
      virtualenv publish/venv
      . publish/venv/bin/activate
      pip install awscli

      aws s3 sync "$EUPS_PKGROOT"/ s3://$EUPS_S3_BUCKET/stack/src/
      '''.replaceFirst("\n","").stripIndent()
    )
  }
}

Common.addNotification(j)
