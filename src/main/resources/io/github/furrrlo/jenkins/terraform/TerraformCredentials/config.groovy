package io.github.furrrlo.jenkins.terraform.TerraformCredentials

f = namespace('/lib/form')

f.entry(field: 'variable', title: _('Variable name')) {
    f.textbox()
}

f.entry(field: 'credentialsId', title: _('Credentials')) {
    f.select()
}
