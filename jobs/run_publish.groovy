import util.Plumber

// note that this job *will not work* unless run-rebuild has been executed at
// least once in order to initialize the env.
def p = new Plumber(name: 'release/run-publish', dsl: this)
p.pipeline().with {
  description('Create and publish EUPS distrib packages.')

  parameters {
    choiceParam('EUPSPKG_SOURCE', ['git', 'package'], 'type of "eupspkg" to create -- "git" should always be used except for a final (non-rc) release')
    stringParam('MANIFEST_ID', null, 'MANIFEST_ID/BUILD_ID/BUILD/bNNNN generated by lsst_build to generate EUPS distrib packages from. Eg. b1935')
    stringParam('EUPS_TAG', null, 'EUPS distrib tag name to publish. Eg. w_2016_08')
    stringParam('PRODUCT', null, 'Whitespace delimited list of EUPS products to tag.')
    stringParam('TIMEOUT', '1', 'build timeout in hours')
    // enable for debugging only
    // booleanParam('NO_PUSH', true, 'Skip s3 push.')
  }
}
