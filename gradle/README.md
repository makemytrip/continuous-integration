# CI platform for Gradle based Projects

## Specification

* Gradle Version = 4.9

## How to improve build time

* Gradle Cache
* Using Nexus for dependency management

## Code verification

Steps executed during verification are checkout of the project and then it will do compilation of the code and run test cases.

```
./gradlew check test
```

1. Test task: Runs the unit tests using JUnit or TestNG.
2. Check task: Aggregate task that performs verification tasks, such as running the tests. Some plugins add their own verification tasks to check.

## Code Submission

```
gradle clean archive -PpackageSuffix=${SUFFIX} -Pbranch=${GERRIT_BRANCH} -PpackagePrefix=${GERRIT_PROJECT}
```

Properties are defined below :-

1. packagePrefix -> Prefix for Deliverable Name (Project’s Name)
2. packageSuffix -> Suffix for Deliverable Name (Branch-Version)
3. branch -> Branch Name passed for project’s use, if any



