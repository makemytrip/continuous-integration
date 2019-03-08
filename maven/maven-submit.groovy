import groovy.sql.Sql

pipeline {
    environment {
        LOB = JOB_NAME.split('-')[0].toUpperCase()
        ITERATION = 0
        SUFFIX = "${GERRIT_BRANCH}-${ITERATION}"
        FORMAT = "tar.gz"
    }
    agent {
        docker {
            image '<docker-ecr-repo>/maven:3.5.4'
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
                checkoutSubmit()
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
            steps {
                readCommit()
                mavenBuild()
                checkArchive()
                dockerize()
            }
        }
        stage('Upload to Apaxy (pre-prod)') {
            when {
                expression { return !(GERRIT_BRANCH =~ 'release') }
            }
            steps {
                uploadArtifactsApaxy(TARS)
            }
        }
        stage('Upload to BizEye') {
            when {
                expression { return (GERRIT_BRANCH =~ 'release') }
            }
            steps {
                databaseWrite()
                uploadArtifactsBizEye(TARS)
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

def checkArchive() {
    TARS = findFiles(glob: "**/*${SUFFIX}.${FORMAT}") ?: error("Aborted due to unavailability of ${FORMAT}…")
}

def createChecksum(ARTIFACT) {
    println "Creating CheckSum for ${ARTIFACT}..."
    BINARY = ARTIFACT.name.toString() // find tar name
    BINARY_PATH = ARTIFACT.path.toString().reverse().drop(BINARY.length()).reverse() ?: "." // find tar directory
    sh "cd ${BINARY_PATH} && md5sum ${BINARY} > ${BINARY}.md5" // cd to target and create checksum for the artifact
    return BINARY
}

def uploadArtifactsApaxy(tars) {
    tars.length.times { // ***
        ARTIFACT = tars[it] // variable 'it' gives the current index value
        BINARY = createChecksum(ARTIFACT) // checksum creation function
        println "Uploading ${BINARY} and ${BINARY}.md5 to apaxy.mmt.com.."
        withCredentials([usernamePassword(credentialsId: 'apaxy-pre-prod', passwordVariable: 'pname', usernameVariable: 'uname')]) {
            sh "ncftpput -R -E -v -u $uname -p $pname apaxy.mmt.mmt /webroot/${LOB}/ ${ARTIFACT}"
            sh "ncftpput -R -E -v -u $uname -p $pname apaxy.mmt.mmt /webroot/${LOB}/ ${ARTIFACT}.md5"
        }
        manager.createSummary("document.png").appendText("<a href='"+"http://apaxy.mmt.com/${LOB}/${BINARY}" + "'>http://apaxy.mmt.com/${LOB}/${BINARY}</a>", false)
    }
}

def uploadArtifactsBizEye(tars) {
    tars.length.times {
        ARTIFACT = tars[it]
        BINARY = createChecksum(ARTIFACT)
        println "Uploading ${BINARY} and ${BINARY}.md5 to bizeye.mmt.com.."
        withCredentials([usernamePassword(credentialsId: 'mum-bizeye', passwordVariable: 'pname', usernameVariable: 'uname')]) {
            sh "lftp -c \"open -u$uname,$pname -p 60021  bizeye.mmt.mmt ; put -O ${LOB} ${ARTIFACT}\""
            sh "lftp -c \"open -u$uname,$pname -p 60021  bizeye.mmt.mmt ; put -O ${LOB} ${ARTIFACT}.md5\""
            s3Upload consoleLogLevel: 'INFO', dontWaitForConcurrentBuildCompletion: false, entries: [[bucket: "mmt-bizeye/${LOB}", excludedFile: '', flatten: true, gzipFiles: false, keepForever: true, managedArtifacts: false, noUploadOnFailure: false, selectedRegion: 'ap-south-1', showDirectlyInBrowser: true, sourceFile: "**/*${SUFFIX}.${FORMAT}*", storageClass: 'STANDARD', uploadFromSlave: false, useServerSideEncryption: false]], pluginFailureResultConstraint: 'FAILURE', profileName: 'openci', userMetadata: [[key: 'project', value: "${GERRIT_PROJECT}"], [key: 'buildNumber', value: "${ITERATION}"], [key: 'jobName', value: "${JOB_NAME}"]]
            artifactVerionWrite(ARTIFACT.name)
        }
        manager.createSummary("document.png").appendText("<a href='"+"http://bizeye.mmt.com:1234/${LOB}/${BINARY}" + "'>http://bizeye.mmt.com:1234/${LOB}/${BINARY}</a>", false)
    }
}

def updateKafka() {
    current_epoch = (currentBuild.startTimeInMillis.intdiv(1000))
    withKafkaLog(kafkaServers: '<kafka-broker-url>', kafkaTopic: 'opentsdb_queue', metadata:'Other info to send..') {
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
            <p><b>Open-CI Link:</b> <a href=${env.BUILD_URL}>${env.JOB_NAME} [${ITERATION}]</a></p>
        </body>
    </html>
    """

    emailext (subject: subject , body: details, to: GERRIT_PATCHSET_UPLOADER_EMAIL)
}

def checkoutSubmit() {
    currentBuild.displayName = currentBuild.displayName + ": ${GERRIT_PROJECT}"
    currentBuild.description = "Branch: ${GERRIT_BRANCH}\nCommitter: ${GERRIT_CHANGE_OWNER_NAME}\nCommit Msg: ${GERRIT_CHANGE_SUBJECT}"
    cleanWs deleteDirs: true, notFailBuild: true, patterns: [[pattern: 'tmp/**', type: 'EXCLUDE'], [pattern: 'node_modules/**', type: 'EXCLUDE']] // *
    checkout([$class: 'GitSCM', branches: [[name: '${GERRIT_BRANCH}']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CloneOption', depth: 0, noTags: true, reference: '', shallow: true, timeout: 10000]], submoduleCfg: [], userRemoteConfigs: [[url: 'ssh://<jenkins-user>@<gerrit-server-url>:29418/${GERRIT_PROJECT}']]]) // **
}

def databaseInit() {
    // Creating a connection to the database
    DB_URL = 'jdbc:mysql://<db-url-goes-here>:3306/open_ci'
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
    def query = 'INSERT INTO artifact_versions (project_name, artifact_name, job_name, build_number, gerrit_id) VALUES (?, ?, ?, ?, ?)'
    def params = [GERRIT_PROJECT, artifact_name, JOB_NAME, BUILD_NUMBER, GERRIT_CHANGE_NUMBER]
    sql.execute query, params
    sql.close()
    println("Database Entry Made for table artifact_versions. [Project: $GERRIT_PROJECT, Artifact: $artifact_name, GERRIT_CHANGE_NUMBER: $GERRIT_CHANGE_NUMBER]")
}

def readCommit() {
  try {
    PROFILE = "-P" + GERRIT_CHANGE_SUBJECT.split('profile-')[1].split('[\\t\\n\\?\\;\\:\\!\\ \\.]')[0]
    println "Appending ${PROFILE} to maven command"
  } catch (Exception err) {
    PROFILE = ""
    println "Using default profile"
  } finally {
    println "Analysed Git Commit Message"
  }
}

def mavenBuild() {
    TIME = new Date()
    sh "mvn clean install -U -Dmaven.repo.local=tmp/.m2 -Dmaven.test.skip=true -DassemblyName=${GERRIT_PROJECT}-${SUFFIX} -DassemblyPrefix=${GERRIT_PROJECT} -DassemblySuffix=${SUFFIX} -DbuildNumber=${ITERATION} -DbuildUser=${GERRIT_PATCHSET_UPLOADER_EMAIL} -DbuildTime=\"${TIME}\" -DbuildBranch=${GERRIT_BRANCH} ${PROFILE}"
}

def dockerize() {
    if((fileExists('Dockerfile')) && (GERRIT_BRANCH == 'release')) {
        DOCKER_JOB = JOB_NAME.split('-')[0] + "-Docker"
        println('Trigger Dockerization')
        def params = currentBuild.rawBuild.getAction(ParametersAction).getParameters()
        build job: DOCKER_JOB, wait: false, parameters: params
    }
}

// *Clean WorkSpace before checking out new code excluding tmp/ folder. Addition of this led to deletion of DeleteDir().

// **Removed the REFSPEC and BuildChooser option from checkoutSCM method as we require code to come directly from the remote git, and not the Gerrit ChangeSet, which may not include the complete code available in remote.

// ***Jenkins Security Plugin requires whitelisting methods that can be used in Groovy Sandbox. n.times is a staticMethod which is not whitelisted by the plugin, so for it to run in Groovy Sandbox, you need to approve it in Manage Jenkins -> In-process Script Approval; search for your methods by signature: xxxxx and manually approve it.
