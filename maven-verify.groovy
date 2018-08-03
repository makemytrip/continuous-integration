// Copyright 2018 MakeMyTrip (Paritosh Anand)

// This file is part of continous-integration.

//  It is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.

//  It is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.

//  You should have received a copy of the GNU General Public License
//  along with continuous-integration.  If not, see <http://www.gnu.org/licenses/>.

import groovy.sql.Sql

pipeline {
    environment {
        LOB = 'default'
        SONAR_DISABLED = 0
        SONAR_ON_FB = 0
        GERRIT_URL = 'ssh://<jenkins-user>@<gerrit-server-url>:29418'   // Enter Gerrit Server URL with Jenkins User. Add SSH Public Key of Jenkins Server to Gerrit Jenkins User for SSH to work.
        DB_URL = '<db-url-goes-here>'   // Enter Database URL for keeping inventory and info whether Sonar Analysis is required or not; this is used only in methods: isSonarDisabled and sonarOnFeatureBranch. Skip this if you are not using the functionality.
    }
    agent {
       docker {
            image '<docker-image-url-goes-here>'    // Enter Docker Image URL with Maven installed.
            label 'linux'
            args '--net=host'
        }
    }
    options {
        timeout(time: 2, unit: 'HOURS')
    }
    stages {
        stage('Checkout') {
            steps {
                readCommit()    // Git commit message is read to check if a feature branch is requesting for adhoc sonar analysis
                mavenCheckoutVerify()   // Checkout Gerrit Project's patch set; pipeline triggered via Jenkins Gerrit Trigger Plugin 
                isSonarDisabled()   // DB is queried to check if Sonar is Enabled for the project or not.
                sonarOnFeatureBranch()  // DB is queried to check if Sonar is Enabled for the project's feature branches or not.
            }
        }
        stage('Test') {
            steps {
                mavenTest() // Runs mvn test command
            }
        }
        stage('Sonar Analysis') {
            when {
                expression { return (!SONAR_DISABLED) && ((ADHOC_SONAR) || (SONAR_ON_FB) || (GERRIT_BRANCH =~ 'release') || (GERRIT_BRANCH == 'integration')) }
            }
            steps {
                mavenSonar()    // Runs Sonar Analysis and JaCoCo Report is uploaded to Sonar Server. Jenkins Sonar-Scanner Plugin is used and Sonar Server is configured for it to work.
            }
        }
        stage('Quality Gate'){
            when {
                expression { return (!SONAR_DISABLED) && ((ADHOC_SONAR) || (SONAR_ON_FB) || (GERRIT_BRANCH =~ 'release') || (GERRIT_BRANCH == 'integration')) }
            }
            steps {
                checkQualityGate()  // Sonar-Scanner Plugin's withSonarQubeEnv ensures that Task-ID for Sonar Analysis is stored and SonarQube Server side WebHooks are added for Jenkins to receive QualityGate Status
            }
        }
    }
    post { 
        always {
            notifyStatus()  // Notify Status to Committer
            updateKafka()   // Push Execution Metadata to Kafka
            unitTestReport()    // Upload Surefire-Reports for Jenkins BlueOcean Plugin to display
        }    
    }
}

def mavenCheckoutVerify() {
    currentBuild.displayName = currentBuild.displayName + ": ${GERRIT_PROJECT}"
    currentBuild.description = "Branch: ${GERRIT_BRANCH}\nCommitter: ${GERRIT_CHANGE_OWNER_NAME}\nCommit Msg: ${GERRIT_CHANGE_SUBJECT}"
    cleanWs deleteDirs: true, notFailBuild: true, patterns: [[pattern: 'tmp/**', type: 'EXCLUDE']]
    checkout([$class: 'GitSCM', branches: [[name: '${GERRIT_BRANCH}']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: true, timeout: 10000], [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], submoduleCfg: [], userRemoteConfigs: [[name: 'origin', refspec: '${GERRIT_REFSPEC}', url: "${GERRIT_URL}/${GERRIT_PROJECT}"]]])
    script { LOB = JOB_NAME.split('-')[0].toUpperCase() }
}

def readCommit() {
  try {
    ADHOC_SONAR = GERRIT_CHANGE_SUBJECT.split('sonar-')[1].split('[\\t\\n\\,\\?\\;\\:\\!\\ \\.]')[0].toBoolean()
    println "Running Sonar on Adhoc Basis"
  } catch (Exception err) {
    ADHOC_SONAR = false
    println "Skipping Sonar"
  } finally {
    println "Analysed Git Commit Message"
  }
}

def databaseInit() {
    // Creating a connection to the database
    DRIVER = 'com.mysql.jdbc.Driver'
    Class.forName(DRIVER)
    withCredentials([usernamePassword(credentialsId: 'openci-db', passwordVariable: 'PWD', usernameVariable: 'USER')]) {
        return Sql.newInstance(DB_URL, USER, PWD, DRIVER)
    }
}

def isSonarDisabled() {
    def sql = databaseInit()
    def query = sql.firstRow "SELECT sonar_disabled AS sonar_disabled_flag FROM project_meta WHERE project_name = '$GERRIT_PROJECT'"
    if (query) {
        SONAR_DISABLED = query.sonar_disabled_flag
    }
    sql.close()
    println("SONAR_DISABLED: $SONAR_DISABLED for Project: $GERRIT_PROJECT")
}

def sonarOnFeatureBranch() {
    def sql = databaseInit()
    def query = sql.firstRow "SELECT sonar_on_fb AS sonar_on_fb_flag FROM project_meta WHERE project_name = '$GERRIT_PROJECT'"
    if (query) {
        SONAR_ON_FB = query.sonar_on_fb_flag
    }
    sql.close()
    println("SONAR_ON_FB: $SONAR_ON_FB for Project: $GERRIT_PROJECT")
}

def mavenTest() {
    sh 'mvn clean test -U -Dmaven.repo.local=tmp/.m2 -Dmaven.test.failure.ignore=false -Dfile.encoding=UTF-8'
}

def mavenSonar() {
    withSonarQubeEnv("code-radar-" + LOB.toLowerCase()) {
        sh 'mvn sonar:sonar -Dsonar.projectName=${GERRIT_PROJECT} -Dsonar.java.coveragePlugin=jacoco -Dsonar.jacoco.ReportPath=target/jacoco-ut.exec -Dsonar.projectKey=${GERRIT_PROJECT} -Dsonar.branch=Verify -U -Dmaven.repo.local=tmp/.m2 -Dmaven.test.failure.ignore=true -Dfile.encoding=UTF-8'
    }
}

def checkQualityGate() {
    timeout(time: 1, unit: 'HOURS') {
        def qg = waitForQualityGate()
        if (qg.status != 'OK' && qg.status != 'WARN') {
            error "Pipeline aborted due to quality gate failure: ${qg.status}"
        }
        if (qg.status == 'WARN') {
            currentBuild.result = 'UNSTABLE'
            println "Marking the build UNSTABLE as it breaches the quality gate WARN state"
        }
    }
}

def updateKafka() {
    current_epoch = (currentBuild.startTimeInMillis.intdiv(1000))
    withKafkaLog(kafkaServers: '<kafka-broker-url>:9092', kafkaTopic: 'opentsdb_queue', metadata:'Other info to send..') {
        echo "put open_ci.jobstatus ${current_epoch} ${currentBuild.duration} buildname=${JOB_NAME} status=${currentBuild.currentResult} lob=${LOB} project=${GERRIT_PROJECT} branch=${GERRIT_BRANCH} "
    }
}

def notifyStatus() {

    def subject = "Open-CI build ${currentBuild.currentResult} for project ${GERRIT_PROJECT}"
    def details = """
    <!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">
    <html xmlns=\"http://www.w3.org/1999/xhtml\">
        <head>
            <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />
            <title>Build Report</title>
            <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>
        </head>
        <body style=\"margin: 0; padding: 0;\">
            <p><b>Build Status:</b> ${currentBuild.currentResult}</p>
            <p><b>Project Name:</b> ${GERRIT_PROJECT}</p>
            <p><b>Branch Name:</b> ${GERRIT_BRANCH}</p>
            <p><b>Gerrit Link:</b> <a href=${GERRIT_CHANGE_URL}> Gerrit PatchSet Link</a></p>
            <p><b>Open-CI Link:</b> <a href=${env.BUILD_URL}>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a></p>
        </body>
    </html>
    """

    emailext (subject: subject , body: details, to: GERRIT_PATCHSET_UPLOADER_EMAIL)
}

def unitTestReport() {
    if(fileExists('target/surefire-reports')) {
        junit 'target/surefire-reports/*.xml'
    }
}

