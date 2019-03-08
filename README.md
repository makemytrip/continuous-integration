# Continous Integration @ MakeMyTrip

## Concept

* Standardization to minimize CI related ops.
* CI system as code + infra that is scalable

## Architecture

* Docker oriented Infra
 * Jenkins master running as a Docker container.
 * Minimal configuration for slave machines as build are running over docker containers
 * Horizontally scalable
* Pipeline based Jobs
 * Stage based execution time and status breakup.
 * Jenkins pipeline code is version controlled via Gerrit/Github


### Auto-scaling & Build Time

* Auto-scaling based on task queue
* Provisioning of an instance in less than a minute
* Auto-Termination if instance idle for >10 mins
* CI Env dependencies spawned as Docker containers & executed within the Docker container.

### Platform support

* Java
 * Code quality via Sonar dashboards and Quality gates and unit tests
 * MMT Private Nexus dependency management
 * Assembly plugin

* NodeJS
 * MMT private NPM repository for dependency management.

* Android

* iOS

* Python

* GoLang

