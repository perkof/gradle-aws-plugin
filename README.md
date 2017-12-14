# Gradle AWS Plugin
This Gradle plugin provides a means of orchestrating the provisioning of AWS infrastructure and managing configuration parameters across environments.

## Getting Started

Apply the AWS plugin as described here. TODO: plugins link.

If you want to use CloudFormation also apply the CloudFormation plugin as described here. TODO: plugins link.

## The Plugins
This repository is an umbrella for three logically separate but related Gradle plugins: `seek.aws`, `seek.config`, and `seek.cloudformation`.

### AWS Plugin
The AWS plugin must be applied to any Gradle project that wishes to use AWS-related tasks. This plugin will in turn apply the Config plugin which allows the AWS tasks to be configured lazily with configuration parameters that are resolved at runtime.

The AWS plugin is configured via a plugin extension named `aws`:

```
aws {
    region 'us-west-1'
    profile 'my-profile'
}
```

The methods of the `aws` extension are described below:

|Method   |Argument type  |Description            |Required|Default
|---------|---------------|-----------------------|--------|-------
|`region` |`String`       |AWS region             |Yes     |-
|`profile`|`String`       |AWS credentials profile|No      |"default"

The `region` property is used to determine the [AWS region](http://docs.aws.amazon.com/general/latest/gr/rande.html) that tasks will operate against by default. All AWS tasks also have an optional `region` property which takes precedence over the region specified in the `aws` extension. Tasks that do not specify a `region` use the region defined in the `aws` extension. The `region` property is not technically required in `aws` but if it is not specified every AWS task must specify the property.

All AWS tasks use the [default credentials provider chain](http://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html). The `profile` property of `aws` can be used if you use named profiles in `~/.aws/credentials` otherwise it does not need to be specified.

The properties of `aws` can use `lookup` methods to lazily set values via configuration. Lookups are described in the next section.

### Config Plugin
The Config plugin is applied by the top level AWS plugin so it does need to be manually applied to a Gradle project. This plugin provides a means of specifying the location of configuration files and then lazily querying their values when tasks are run. It also provides a means of lazily querying [AWS Parameter Store](http://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-paramstore.html) and [CloudFormation stack outputs](http://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/outputs-section-structure.html).

The Config plugin is configured via a plugin extension named `config`:

```
config {
    files fileTree('config').include('*.conf')
}

```
The methods of the `config` extension are described below:

|Method                 |Argument type   |Description                               |Required|Default
|-----------------------|----------------|------------------------------------------|--------|-------
|`naming`                |`String`        |Config file naming convention             |No      |environment
|`files`                |`FileCollection`|Full set of configuration files           |No      |-
|`addFiles`             |`FileCollection`|Adds configuration files                  |No      |-
|`allowProjectOverrides`|`Boolean`       |Whether project properties override config|No      |`true`
|`allowCommonConfig`    |`Boolean`       |Whether common config is allows           |No      |`true`
|`commonConfigName`     |`String`        |Name of the common configuration file     |No      |"common"

The `naming` property specifies how configuration files are named. The default is "environment". This means that at runtime the value of the `environment` Gradle property will determine the name of the configuration file(s) to load. If `environment=production` the Config plugin will load any files named `production.conf`, `production.json`, and `production.properties`.

The `naming` property does not have to be one dimensional. For example, consider a service that is deployed to multiple regions within an environment. A possible naming scheme is "environment.region". At runtime the values of the properties `environment` and `region` will be resolved to determine the name of the config files. If `environment=production` and `region=ap-southeast-2` then the plugin will look for config files named `production.ap-southeast-2.(conf|json|properties)`.

The `files` property sets the `FileCollection` that contains all configuration files to be used. `addFiles` can be used to additively accumulate files. For example, in a multi-project Gradle build the root project might set top level common configuration files and then subprojects might add their specific configuration files using `addFiles`.

The `allowProjectOverrides` property specifies whether Gradle project properties should be considered before looking up configuration files. This is useful for overriding configuration values on the command line. For example, if `allowProjectOverrides` is `true` (the default) then:

```
gradle createOrUpdateStack -PbucketName=my-bucket
```

would use the value of `bucketName` specified on the command line rather than the value of `bucketName` found in configuration.

The `allowCommonConfig` property specifies whether common configuration files are allowed. An application that is deployed to multiple environments will often still have a core set of configuration parameters that are common to all environments. When this property is true (the default) the plugin will load any configuration files that match `commonConfigName` and attempt to resolve parameters there if they can not be resolved in environment specific files.

#### Using Lookups
The Config plugin uses "lookups" to resolve configuration keys to values at runtime. Lookups can be specified as arguments to AWS task properties so that the tasks are configured with different values depending on the environment, region, or any other dimension, they are running against.

Lookups are used by statically importing the methods of `seek.aws.config.Lookup`:

```
import static seek.aws.config.Lookup.*
```

This class defines three static methods that can be used to create lookups:

|Scala function signature
|------------------------
|`def lookup(key: String): Lookup`
|`def stackOutput(stackName: String, key: String): Lookup`
|`def parameterStore(key: String): Lookup`


Java versions of the method signatures are shown below:

|Java method signature
|---------------------
|`public static Lookup lookup(String)`
|`public static Lookup stackOutput(String stackName, String key)`
|`public static Lookup parameterStore(String key)`

Each of these methods returns a `Lookup` object which is resolved at runtime. The `lookup` method is the most general. It returns a `Lookup` object that when run will attempt to resolve the specified key first using Gradle properties, then using configuration files, and finally using the AWS parameter store. More details on configuration resolution are discussed in the next section.

The `stackOutput` method returns a `Lookup` that will attempt to resolve itself by querying CloudFormation for the output key of the specified stack.

The `parameterStore` method returns a `Lookup` that will attempt to resolve itself by querying AWS Parameter Store. 

All AWS tasks can make use of the Config plugin to resolve values. For example, consider the following task definition:

```
task uploadLambdaJar(type: UploadFile, dependsOn: shadowJar) {
    bucket lookup('buildBucket')
    key lambdaArtefactKey
    file shadowJar.archivePath
}
```
This task is responsible for uploading a jar file containing Lambda function code. The `bucket` property may differ across environments in which case the `lookup` method can be used to tell the task the property needs to be lazily resolved at runtime.

#### Config Resolution
The Config plugin uses the [Lightbend config library](https://github.com/lightbend/config) (formerly Typesafe config) to parse configuration files. For example, consider the following Gradle file snippet:

```
import static seek.aws.config.Lookup.lookup
import seek.aws.s3.UploadFile

ext {
    environment = 'development'
}

config {
    addFiles fileTree('config1').include('*')
    addFiles fileTree('config2').include('*')
    addFiles fileTree('config3').include('*')
}

task uploadLambdaJar(type: UploadFile, dependsOn: shadowJar) {
    bucket lookup('buildBucket')
    key lambdaArtefactKey
    file shadowJar.archivePath
}
```

The resolution of `buildBucket` is lazy meaning that it will take place when `uploadLambdaJar` runs not during Gradle's [initialisation phase](https://docs.gradle.org/current/userguide/build_lifecycle.html) which is the default behaviour of Task properties. This lazy behaviour makes the `parameterStore` and `stackOutput` methods possible (described above) since the Parameter Store values and CloudFormation stacks might not exist at initialisation time.

In the case of the above example using the `lookup` method the order of resolution is as follows:

1. Look for a project property named `buildBucket` (unless `config.allowProjectOverrides` is set to `false`)
1. Look for a configuration key named `buildBucket`, `build-bucket`, `build.bucket`, or `build_bucket` in a file named `development.(conf|json|properties)` in directory `config3`
1. Look for a configuration key named `buildBucket`, `build-bucket`, `build.bucket`, or `build_bucket` in a file named `common.(conf|json|properties)` in directory `config3` (unless `config.allowCommonConfig` is set to `false`)
1. Look for a configuration key named `buildBucket`, `build-bucket`, `build.bucket`, or `build_bucket` in a file named `development.(conf|json|properties)` in directory `config2`
1. Look for a configuration key named `buildBucket`, `build-bucket`, `build.bucket`, or `build_bucket` in a file named `common.(conf|json|properties)` in directory `config2` (unless `config.allowCommonConfig` is set to `false`)
1. Look for a configuration key named `buildBucket`, `build-bucket`, `build.bucket`, or `build_bucket` in a file named `development.(conf|json|properties)` in directory `config1`
1. Look for a configuration key named `buildBucket`, `build-bucket`, `build.bucket`, or `build_bucket` in a file named `common.(conf|json|properties)` in directory `config1` (unless `config.allowCommonConfig` is set to `false`)

The resolution ends when the key is found or all sources are exhausted.

### CloudFormation Plugin
The CloudFormation plugin should be applied to projects that provision CloudFormation stacks.

The CloudFormation plugin is configured via a plugin extension named `cloudFormation`:

```
cloudFormation {
    stackName project.name
    templateFile file('src/main/cloudformation/application.yaml')
    policyFile file('src/main/cloudformation/policy.json')
    tags (['Owner', 'Project', 'Version'])
}
```

The methods of the `cloudFormation` extension are described below:

|Method                   |Argument type                       |Description                            |Required|Default
|-------------------------|------------------------------------|---------------------------------------|--------|-------
|`stackName`              |`String`                            |Stack name                             |Yes     |-
|`templateFile`           |`File`                              |Stack template file                    |Yes     |-
|`policyFile`             |`File`                              |Stack policy file                      |No      |No policy
|`parameters`             |`Map[String, Any]`                  |Stack parameters map                   |No      |Config driven
|`tags`                   |`Map[String, Any]` or `List[String]`|Stack tag map or tag name list         |No      |No tags
|`stackWaitTimeoutSeconds`|`Int`                               |Timeout for stack operations in seconds|No      |15 mins

The `stackName` property specifies the name for the CloudFormation stack. This property can be specified using a `lookup`, a Gradle property, or hard-coded. The plugin creates a task called `createOrUpdateStack` (detailed below). When this task is run it checks for the existence of a stack with this name - if it exists an update operation is performed; if it does not exist a create operation is performed.

The `templateFile` property specifies a `java.io.File` that references the YAML or JSON CloudFormation template for the stack. Similarly, the optional `policyFile` property specifies a stack policy file.

The `parameters` property can be used to specify a map of key-value pairs to be used as stack parameters. The map values can be hard-coded values or lookups. For example:

```
cloudFormation {
    ...
    parameters ([
        BuildBucket: lookup('buildBucket'),
        LambdaArtefactKey: "${buildPrefix}/${service}.jar",
        LambdaBatchSize: lookup('lambdaBatchSize'),
        TableName: stackOutput('scaffolding', 'TableName'),
        KinesisStream: parameterStore('eventStream')
    ])
    ...
}
```

The CloudFormation plugin resolves stack parameters by parsing the `Parameters` section of the template file and then attempts to resolve each parameter in the following order:

1. Project properties (unless `config.allowCommonConfig` is set to `false`)
1. Parameters map specified in `cloudFormation.parameters`
1. Configuration files

If all stack parameters are defined in configuration files or project properties then the `parameters` property of `cloudFormation` is unnecessary and not really recommended. It's main use is if you need to feed in parameter values from AWS Parameter Store or from a CloudFormation stack output.

The `tags` property can be specified as a map (in the same fashion as the `parameters` property) or as a list of tag keys. If a list is specified each tag key is looked up using the Config plugin.

The CloudFormation plugin applies the following tasks to the project:

|Task name            |Description
|---------------------|-----------
|`createOrUpdateStack`|Creates or updates the stack defined in the `cloudFormation` extension
|`deleteStack`        |Deletes the stack defined in the `cloudFormation` extension if it exists
|`verifyStack`        |Verifies that all stack parameters and tags specified in the template file are available and if they are prints them

## The Tasks

### S3 Tasks
### CloudFormation Tasks


## Examples
