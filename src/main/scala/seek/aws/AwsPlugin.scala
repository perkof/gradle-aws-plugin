package seek.aws

import org.gradle.api.{Plugin, Project}
import seek.aws.config.ConfigPlugin

class AwsPlugin extends Plugin[Project] {

  override def apply(project: Project): Unit = {
    project.getPlugins.apply(classOf[ConfigPlugin])
    project.getExtensions.create("aws", classOf[AwsPluginExtension], project)
  }
}
