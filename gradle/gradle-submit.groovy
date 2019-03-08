import groovy.sql.Sql

pipeline {
    environment {
        LOB = JOB_NAME.split('-')[0].toUpperCase()
        ITERATION = 0
        SUFFIX = "${GERRIT_BRANCH}-${ITERATION}"
        FORMAT = "tar.gz"
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
                gradleCheckoutSubmit()
                script {
                    if (GERRIT_BRANCH =~ 'release') {
                        databaseRead()
                    } else {
                        ITERATION = BUILD_NUMBER
                    }
                    SUFFIX = "${GERRIT_BRANCH}-${ITERATION}"
                }
            }
        }
        stage('Build and Archive') {
            steps{
                sh "gradle clean archive -PpackageSuffix=${SUFFIX} -Pbranch=${GERRIT_BRANCH} -PpackagePrefix=${GERRIT_PROJECT}"
                createChecksum()
            }
        }
        stage('Upload to Apaxy (pre-prod)') {
            when {
                expression { return !(GERRIT_BRANCH =~ 'release') }
            }
            steps {
                uploadArtifactsApaxy()
            }
        }
        stage('Upload to BizEye') {
            when {
                expression { return (GERRIT_BRANCH =~ 'release') }
            }
            steps {
                uploadArtifactsBizEye()
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

def createChecksum() {
def tars = findFiles(glob: "**/*${SUFFIX}.tar.gz") // via using Jenkins Plugin: Pipeline Utility Steps
    if (!tars.length) {
        currentBuild.result = 'FAILURE'
        error('Aborting due to unavailability of tar.gz…')
    }
    tars.length.times { // ***
        ARTIFACT = tars[it] // variable 'it' gives the current index value
        println "Creating CheckSum for ${ARTIFACT}..."
        BINARY = ARTIFACT.name.toString() // find tar name
        BINARY_PATH = ARTIFACT.path.toString().reverse().drop(BINARY.length()).reverse() ?: '.' // tar directory
        sh "cd ${BINARY_PATH} && md5sum ${BINARY} > ${BINARY}.md5" // cd to target and create checksum for the artifact
        //archiveArtifacts "${ARTIFACT}*" // archive artifacts and associated files
               if ( "${GERRIT_BRANCH}" =~ 'release') {
                script{ manager.createSummary("document.png").appendText("<a href='"+"http://bizeye.mmt.com:1234/${LOB}/${BINARY}" + "'>http://bizeye.mmt.com:1234/${LOB}/${BINARY}</a>", false) }
            } else {
                 script{ manager.createSummary("document.png").appendText("<a href='"+"http://apaxy.mmt.com/${LOB}/${BINARY}" + "'>http://apaxy.mmt.com/${LOB}/${BINARY}</a>", false) }
            }
    }
}

def uploadArtifactsApaxy() {
    def tars = findFiles(glob: "**/*${SUFFIX}.tar.gz")
    tars.length.times {
        ARTIFACT = tars[it]
        println "Uploading ${ARTIFACT} and its checksum to apaxy.mmt.com.."
        withCredentials([usernamePassword(credentialsId: 'apaxy-pre-prod', passwordVariable: 'pname', usernameVariable: 'uname')]) {
            sh "ncftpput -R -E -v -u $uname -p $pname apaxy.mmt.mmt /webroot/${LOB}/ ${ARTIFACT}"
            sh "ncftpput -R -E -v -u $uname -p $pname apaxy.mmt.mmt /webroot/${LOB}/ ${ARTIFACT}.md5"
        }
    }
}

def uploadArtifactsBizEye() {
    def tars = findFiles(glob: "**/*${SUFFIX}.tar.gz")
    tars.length.times {
        ARTIFACT = tars[it]
        println "Uploading ${ARTIFACT} and its checksum to bizeye.mmt.com.."
        withCredentials([usernamePassword(credentialsId: 'mum-bizeye', passwordVariable: 'pname', usernameVariable: 'uname')]) {
            sh "lftp -c \"open -u$uname,$pname -p 60021  ${BIZEYE_MUM} ; put -O ${LOB} ${ARTIFACT}\""
            sh "lftp -c \"open -u$uname,$pname -p 60021  ${BIZEYE_MUM} ; put -O ${LOB} ${ARTIFACT}.md5\""
            sh "lftp -c \"open -u$uname,$pname -p 60021  ${BIZEYE_CHN} ; put -O ${LOB} ${ARTIFACT}\""
            sh "lftp -c \"open -u$uname,$pname -p 60021  ${BIZEYE_CHN} ; put -O ${LOB} ${ARTIFACT}.md5\""
            s3Upload consoleLogLevel: 'INFO', dontWaitForConcurrentBuildCompletion: false, entries: [[bucket: "mmt-bizeye/${LOB}", excludedFile: '', flatten: true, gzipFiles: false, keepForever: true, managedArtifacts: false, noUploadOnFailure: false, selectedRegion: 'ap-south-1', showDirectlyInBrowser: true, sourceFile: "**/*${ARTIFACT}", storageClass: 'STANDARD', uploadFromSlave: false, useServerSideEncryption: false]], pluginFailureResultConstraint: 'FAILURE', profileName: 'openci', userMetadata: [[key: 'project', value: "${GERRIT_PROJECT}"], [key: 'buildNumber', value: "${BUILD_NUMBER}"], [key: 'jobName', value: "${JOB_NAME}"]]
            s3Upload consoleLogLevel: 'INFO', dontWaitForConcurrentBuildCompletion: false, entries: [[bucket: "mmt-bizeye/${LOB}", excludedFile: '', flatten: true, gzipFiles: false, keepForever: true, managedArtifacts: false, noUploadOnFailure: false, selectedRegion: 'ap-south-1', showDirectlyInBrowser: true, sourceFile: "**/*${ARTIFACT}.md5", storageClass: 'STANDARD', uploadFromSlave: false, useServerSideEncryption: false]], pluginFailureResultConstraint: 'FAILURE', profileName: 'openci', userMetadata: [[key: 'project', value: "${GERRIT_PROJECT}"], [key: 'buildNumber', value: "${BUILD_NUMBER}"], [key: 'jobName', value: "${JOB_NAME}"]]
            artifactVerionWrite(ARTIFACT.name)
        }
    }
}

def updateKafka() {
    current_epoch = (currentBuild.startTimeInMillis.intdiv(1000))
    withKafkaLog(kafkaServers: "${MON_KAFKA_BROKER}", kafkaTopic: 'opentsdb_queue', metadata:'Other info to send..') {
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

def databaseInit() {
    // Creating a connection to the database
    DB_URL = "jdbc:mysql://${DEVOPS_RDS}:3306/open_ci"
    DRIVER = 'com.mysql.jdbc.Driver'
    Class.forName(DRIVER)
    withCredentials([usernamePassword(credentialsId: 'openci-db', passwordVariable: 'PWD', usernameVariable: 'USER')]) {
        return Sql.newInstance(DB_URL, USER, PWD, DRIVER)
    }
}

def databaseRead() {
    def sql = databaseInit()
    def query = sql.firstRow 'SELECT IFNULL(SUM(version + 1), 1) AS iteration FROM build_version WHERE project_name = ? AND branch = ?', GERRIT_PROJECT, GERRIT_BRANCH
    ITERATION = query.iteration
    sql.close()
    println("Iteration: $ITERATION for Project: $GERRIT_PROJECT Branch: $GERRIT_BRANCH")
}

def databaseWrite() {
    def sql = databaseInit()
    if (ITERATION == 1) {
        query = 'INSERT INTO build_version (version, project_name, branch) VALUES (?, ?, ?)'
    } else {
        query = 'UPDATE build_version SET version = ? WHERE project_name = ? AND branch = ?'
    }
    def params = [ITERATION, GERRIT_PROJECT, GERRIT_BRANCH]
    sql.execute query, params
    sql.close()
    println("Database Updated. [Iteration: $ITERATION, Project: $GERRIT_PROJECT, Branch: $GERRIT_BRANCH]")
}

def artifactVerionWrite(artifact_name) {
    def sql = databaseInit()
    def query = 'INSERT INTO artifact_versions (project_name, artifact_name, job_name, build_number) VALUES (?, ?, ?, ?)'
    def params = [GERRIT_PROJECT, artifact_name, JOB_NAME, BUILD_NUMBER]
    sql.execute query, params
    sql.close()
    println("Database Entry Made for table artifact_versions. [Project: $GERRIT_PROJECT, Artifact: $artifact_name]")
}

def gradleCheckoutSubmit() {
    currentBuild.displayName = currentBuild.displayName + ": ${GERRIT_PROJECT}"
    currentBuild.description = "Branch: ${GERRIT_BRANCH}\nCommitter: ${GERRIT_CHANGE_OWNER_NAME}\nCommit Msg: ${GERRIT_CHANGE_SUBJECT}"
    cleanWs deleteDirs: true, notFailBuild: true, patterns: [[pattern: 'node_modules/**', type: 'EXCLUDE']]
    checkout([$class: 'GitSCM', branches: [[name: '${GERRIT_BRANCH}']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: true, timeout: 10000]], submoduleCfg: [], userRemoteConfigs: [[url: 'ssh://jenkins@gerrit.mmt.com:29418/${GERRIT_PROJECT}']]])
    LOB = JOB_NAME.split('-')[0].toUpperCase()
}