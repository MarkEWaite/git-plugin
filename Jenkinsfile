node {
  // Mark the code checkout 'stage'....
  stage 'Checkout'

  // Checkout code from repository
  checkout scm

  // Get the java 7 tool.
  // ** NOTE: This 'oracle-java-7' JDK tool must be configured
  // **       in the global configuration.
  env.JAVA_HOME="${tool 'oracle-java-7'}"
  if (isUnix()) {
    env.PATH="${env.JAVA_HOME}/bin:${env.PATH}"
  } else {
    env.PATH="${env.JAVA_HOME}\\bin;${env.PATH}"
  }

  // Get the maven tool.
  // ** NOTE: This 'maven-latest' maven tool must be configured
  // **       in the global configuration.
  env.M2_HOME="${tool 'maven-latest'}"
  if (isUnix()) {
    env.PATH="${env.M2_HOME}/bin:${env.PATH}"
  } else {
    env.PATH="${env.M2_HOME}\\bin;${env.PATH}"
  }

  // Mark the code build 'stage'....
  stage 'Build'
  // Run the maven build
  if (isUnix()) {
    sh "mvn clean -Dmaven.test.failure.ignore=true -DTEST_ALL_CREDENTIALS=false install"
  } else {
    bat "mvn clean -Dmaven.test.failure.ignore=true -DTEST_ALL_CREDENTIALS=false install"
  }

  // Results stage - remember things about the build ....
  stage 'Results'
  step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
  step([$class: 'ArtifactArchiver', artifacts: '**/target/*.*pi', fingerprint: true])
}
