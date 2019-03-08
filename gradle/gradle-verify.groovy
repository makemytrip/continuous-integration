pipeline {
    environment {
        LOB = 'default'
        CI = "continous"
    }
    agent {
       docker {
            image "${DOCKER_REPO}/gradle:4.9"
            label 'linux'
            args '--net=host'
        }
    }
    options {
        timeout(time: 2, unit: 'HOURS')
        timestamps()
    }
    stages {
        stage('Checkout') {
            steps {
                mavenCheckoutVerify()
            }
        }
        stage('Test') {
            steps {
                sh './gradlew check test'
            }
        }
    }
    post {
        always {
            notifyStatus()
            updateKafka()
        }
    }
}

def updateKafka() {
    current_epoch = (currentBuild.startTimeInMillis.intdiv(1000))
    withKafkaLog(kafkaServers: "${MON_KAFKA_BROKER}", kafkaTopic: 'opentsdb_queue', metadata:'Other info to send..') {
        echo "put open_ci.jobstatus ${current_epoch} ${currentBuild.duration} buildname=${JOB_NAME} status=${currentBuild.currentResult} lob=${LOB} project=${GERRIT_PROJECT} branch=${GERRIT_BRANCH}"
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

def mavenCheckoutVerify() {
    currentBuild.displayName = currentBuild.displayName + ": ${GERRIT_PROJECT}"
    currentBuild.description = "Branch: ${GERRIT_BRANCH}\nCommitter: ${GERRIT_CHANGE_OWNER_NAME}\nCommit Msg: ${GERRIT_CHANGE_SUBJECT}"
    cleanWs deleteDirs: true, notFailBuild: true, patterns: [[pattern: 'tmp/**', type: 'EXCLUDE']]
    checkout([$class: 'GitSCM', branches: [[name: '${GERRIT_BRANCH}']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: true, timeout: 10000], [$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], submoduleCfg: [], userRemoteConfigs: [[name: 'origin', refspec: '${GERRIT_REFSPEC}', url: 'ssh://jenkins@gerrit.mmt.com:29418/${GERRIT_PROJECT}']]])
    //checkout([$class: 'GitSCM', branches: [[name: '${GERRIT_BRANCH}']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'jenkins-registry', name: 'origin', refspec: '${GERRIT_REFSPEC}', url: 'http://gerrit.mmt.com/a/${GERRIT_PROJECT}']]])
    script { LOB = JOB_NAME.split('-')[0].toUpperCase() }
}