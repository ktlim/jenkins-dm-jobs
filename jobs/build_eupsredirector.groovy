import util.Plumber

def p = new Plumber(name: 'sqre/infra/build-eupsredirector', dsl: this)
p.pipeline().with {
  description('Constructs docker lsstsqre/eupsredirector images.')

  parameters {
    booleanParam('NO_PUSH', false, 'Do not push image to docker registry.')
    booleanParam('LATEST', false, 'Also push to docker registry with "latest" tag.')
  }
}
