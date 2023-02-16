# Jenkins Terraform Agent

## Introduction

Jenkins plugin to dynamically provision agents using Terraform.

The infrastructure specified will be created when needed and destroyed when it's no longer used.

## Getting started

First, configure a Terraform installation as described [here](https://plugins.jenkins.io/terraform/#plugin-content-configuration). 

Then you can proceed to fill in the configuration. In order to do that, open the Jenkins UI and navigate to
**Manage Jenkins -> Manage Nodes and Clouds -> Configure Clouds -> Add a new cloud -> Terraform Cloud**.

The plugin will automatically provide to the Terraform configuration the default variables which can be used to launch 
inbound agents:
- jenkins_url: Jenkins controller url
- jenkins_agent_name: the name of the Jenkins agent
- jenkins_agent_secret: the secret key for authentication
- jenkins_websocket: whether agents will connect over HTTP(S) rather than the Jenkins service TCP port.
- jenkins_agent_workdir: the working directory the agent neesd to use

These variables need to be defined in the Terraform config:
```terraform
variable "jenkins_url" {}
variable "jenkins_websocket" {}
variable "jenkins_agent_name" {}
variable "jenkins_agent_secret" {}
variable "jenkins_agent_workdir" {}
```
These aren't really used for anything else, so you can also decide to not use them and just hardcode
values inside the Terraform config.

You can also provide credentials directly from Jenkins. Supported types are:
- Username/password: you specify a var name in the Jenkins UI, the plugin will add a
  `var_name_usr` and `var_name_pwd` variables to be used inside the script 
- Secret text (Token-based authentication): will be provided in the script with the exact name specified in
  the UI


## Examples

- [Linode + sysbox + cache volume](./docs/linode-sysbox.md)