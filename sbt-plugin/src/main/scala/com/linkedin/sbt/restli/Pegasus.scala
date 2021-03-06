/*
   Copyright (c) 2014 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.sbt.restli


import sbt._
import sbt.Keys._
import sbt.ConfigKey.configurationToKey
import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import java.io.File.pathSeparator
import com.linkedin.pegasus.generator.PegasusDataTemplateGenerator
import xsbti.{Maybe, Severity, Position, Problem}

/**
 * Runs PegasusDataTemplateGenerator
 */
class PegasusProject(val project: Project) extends Pegasus {

  def compilePegasus() =
    project
    .settings(pegasusArtifacts : _*)
    .settings(
      // Don't scala-version name-mangle this project, since it is intended equally for Java use
      // and does not contain Scala-generated bytecode.
      crossPaths := false,
      autoScalaLibrary := false,

      restliPegasusPdscDir := (sourceDirectory in Compile).value / "pegasus",
      restliPegasusResolverPath := {
        val resolverPathFiles = Seq(restliPegasusPdscDir.value.getAbsolutePath) ++
                (managedClasspath in Compile).value.map(_.data.getAbsolutePath) ++
                (internalDependencyClasspath in Compile).value.map(_.data.getAbsolutePath) // adds in .pdscs from projects that this project .dependsOn
        resolverPathFiles.mkString(pathSeparator)
      },
      restliPegasusJavaDir := (sourceDirectory in Compile).value / "codegen",
      restliPegasusPdscFiles := (restliPegasusPdscDir.value ** PdscFileGlobExpr).get,
      restliPegasusJavaFiles := (restliPegasusJavaDir.value ** JavaFileGlobExpr).get,

      watchSources := (restliPegasusPdscDir.value ** PdscFileGlobExpr).get,

      // For documentation of this approach see http://www.scala-sbt.org/release/docs/Detailed-Topics/Classpaths
      sourceGenerators in Compile <+= (restliPegasusGenerate in Compile).task,

      restliPegasusGenerate in Compile := pegasusGenerator.value,
      unmanagedSourceDirectories in Compile += restliPegasusPdscDir.value,
      restliPegasusCacheSources := streams.value.cacheDirectory / "pdsc.sources",
      managedSourceDirectories in Compile  += restliPegasusJavaDir.value,

      exportedProducts in Compile := {
        val p: Attributed[sbt.File] = Attributed.blank(restliPegasusPdscDir.value)
        (exportedProducts in Compile).value ++ Seq(p)
      },

      // data template jars are expected to contain the source pdsc's in the pegasus/ directory of the jar.
      Keys.mappings in (Compile, packageBin) ++= {
        val pdscFiles = (restliPegasusPdscDir.value ** PdscFileGlobExpr).get
        pdscFiles pair rebase(restliPegasusPdscDir.value, "pegasus/")
      }
   )
}

trait Pegasus extends Restli {
  val restliPegasusPdscDir = settingKey[File]("Source directory containing .pdsc files")
  val restliPegasusResolverPath = taskKey[String](
    """Sets the System property for Pegasus/Avro projects: generator.resolver.path before running
      |the generator. The format is the same as the format for Java classpath (':' seperated).
      |File system paths and .jar paths may be used.""".stripMargin)

  val restliPegasusJavaDir = settingKey[File]("Java files based on pegasus files are put here")
  val restliPegasusCacheSources = taskKey[File]("Caches .pdsc sources")
  val restliPegasusPdscFiles = taskKey[Seq[File]]("The pegasus source files")
  val restliPegasusJavaFiles = taskKey[Seq[File]]("The java files generated by pegasus")
  val restliPegasusGenerate = taskKey[Seq[File]]("Runs pegasus")

  //package
  val packageDataModel = taskKey[File]("Produces a data model jar containing only pdsc files")

  // Returns settings that can be applied to a project to cause it to package the Pegasus artifacts.
  def pegasusArtifacts = {
    def packageDataModelMappings = restliPegasusPdscDir.map{ (dir) =>
      mappings(dir, PdscFileGlobExpr)
    }

    // The resulting settings create the two packaging tasks, put their artifacts in specific Ivy configs,
    // and add their artifacts to the project.

    val defaultConfig = config("default").extend(Runtime).describedAs("Configuration for default artifacts.")

    val dataTemplateConfig = new Configuration("dataTemplate", "pegasus data templates",
                                             isPublic=true,
                                             extendsConfigs=List(Compile),
                                             transitive=true)

    Defaults.packageTaskSettings(packageDataModel, packageDataModelMappings) ++
    restliArtifactSettings(packageDataModel)("dataModel") ++
    Seq(
      packagedArtifacts <++= Classpaths.packaged(Seq(packageDataModel)),
      artifacts <++= Classpaths.artifactDefs(Seq(packageDataModel)),
      ivyConfigurations ++= List(dataTemplateConfig, defaultConfig),
      artifact in (Compile, packageBin) ~= { (art: Artifact) =>
        art.copy(configurations = art.configurations ++ List(dataTemplateConfig))
      }
    )
  }

  //transforms a Project to a PegasusProject if needed, i.e. when you call a method that exists only on PegasusProject
  implicit def projectToPegasusProject(project : Project) = new PegasusProject(project)

  val pegasusGenerator = Def.task {
    val s = streams.value
    val sourceDir = restliPegasusPdscDir.value
    val javaDir = restliPegasusJavaDir.value
    val resolverPath = restliPegasusResolverPath.value
    val cacheFileSources = restliPegasusCacheSources.value

    if (!sourceDir.exists()) {
      throw new MessageOnlyException("Pegasus source directory does not exist: " + sourceDir)
    }

    val sourceFiles = (sourceDir ** PdscFileGlobExpr).get
    s.log.debug("source files: (" + sourceFiles.size + ")" + sourceFiles.toList)

    val previousJavaFiles = (javaDir ** JavaFileGlobExpr).get
    val (anyFilesChanged, cacheSourceFiles) = {
      prepareCacheUpdate(cacheFileSources, sourceFiles, s)
    }
    s.log.debug("detected changed files: " + anyFilesChanged)
    if (anyFilesChanged) {
      javaDir.mkdirs()
      val generated = try {
        val pdscFiles = sourceFiles.map(_.getAbsolutePath())
        s.log.debug("found pdsc files: " + pdscFiles.toString)
        PegasusDataTemplateGenerator.run(resolverPath, null, null, javaDir.getAbsolutePath, pdscFiles.toArray)
      } catch {
        case e: java.io.IOException => {
          e.getMessage match {
            case JsonParseExceptionRegExp(source, line, column) =>
              throw new RestliCompilationException(
                Some(file(source)),
                "JSON parse error in " + source + ": line: "  +  line.toInt + ", column:  " + column.toInt,
                Option(line.toInt), Option(column.toInt),
                Severity.Error)
            case _ =>
              throw new MessageOnlyException("Restli generator error" + "Error message: " + e.getMessage)
          }
        }
        case e: Throwable => {
          throw e
        }
      }
      val generatedJavaFiles = generated.getModifiedFiles.asScala.toSeq ++ generated.getTargetFiles.asScala.toSeq
      s.log.debug("generated java files: " + generatedJavaFiles.toList.toString)

      val staleFiles = previousJavaFiles.sorted.diff(generatedJavaFiles.sorted)
      s.log.debug("deleting stale files: " + staleFiles)
      IO.delete(staleFiles)

      cacheSourceFiles() //we cache the source files here because we are successful
      generatedJavaFiles
    } else {
      previousJavaFiles
    }
  }
}
