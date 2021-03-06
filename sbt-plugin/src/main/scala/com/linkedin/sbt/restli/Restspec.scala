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

import scala.collection.JavaConverters._
import com.linkedin.restli.tools.idlcheck.{RestLiResourceModelCompatibilityChecker, CompatibilityLevel}
import com.linkedin.restli.tools.snapshot.check.RestLiSnapshotCompatibilityChecker
import com.linkedin.pegasus.generator.GeneratorResult
import scala.Some
import com.linkedin.restli.tools.compatibility.CompatibilityInfoMap
import com.linkedin.restli.tools.idlgen.RestLiResourceModelExporter
import com.linkedin.restli.tools.snapshot.gen.RestLiSnapshotExporter
import com.linkedin.restli.internal.server.model.ResourceModelEncoder.DocsProvider
import com.linkedin.restli.tools.scala.ScalaDocsProvider

case class IdlAndSnapshotFiles(idlFiles: Seq[File], snapshotFiles: Seq[File])

/**
 * Provides idl (.restspec.json) generation, snapshot generation, and compatiblity checking.
 *
 * If any changes have been made to .pdscs or rest.li "resource" implementation classes, idl and snapshots are
 * regenerated.  If there are any changes to the idl or snapshots, compatibility checking is performed.
 * And if compatibility checks pass, idl and snapshots are published to the provided apiProject.
 */
class RestspecProject(val project : Project) extends Restspec with Pegasus {

  def compileRestspec(apiName: String, apiProject: Project, resourcePackages: List[String], dataTemplateProject: Project, compatMode: String = "equivalent") = {
    def pathFromDir(dir: sbt.File) = List(dir.getAbsolutePath)

    project
    .settings(
      restliRestspecApiName := apiName,
      restliRestspecResourcePackages := resourcePackages,
      restLiCompatMode := compatMode,
      restliRestspecResolverPath := (restliPegasusResolverPath in Compile in dataTemplateProject).value,

      restliRestspecResourceAndPdscInfoCache := streams.value.cacheDirectory / "idlgen.classfiles",

      restliRestspecJsonInfoCache := streams.value.cacheDirectory / "idlgen.jsonfiles",
      restliSnapshotJsonInfoCache := streams.value.cacheDirectory / "snapshot.jsonfiles",

      restliRestspecGeneratedJsonDir := target.value  / "restspec_json",
      restliSnapshotGeneratedJsonDir := target.value  / "snapshot_json",
      restliRestspecPublishedJsonDir := (sourceDirectory in apiProject).value / "idl",
      restliSnapshotPublishedJsonDir := (sourceDirectory in apiProject).value / "snapshot",

      restliRestspecResourceSourcePaths := pathFromDir((sourceDirectory in Compile).value),

      restliRestspecResourceProducts := (products in Compile).value,

      restliRestspecResourceClasspath := (fullClasspath in Compile).value,

      restliResourceModelExport in Compile := resourceModelExporter.value,
      restLiRestspecPublish in Compile <<= restspecPublisher triggeredBy(compile in Compile),
      restLiSnapshotPublish in Compile <<= snapshotPublisher triggeredBy(compile in Compile),

      unmanagedSourceDirectories in Compile += restliRestspecGeneratedJsonDir.value,
      unmanagedSourceDirectories in Compile += restliSnapshotGeneratedJsonDir.value
    )
  }
}

trait Restspec extends Restli {

  val restLiRestspecPublish = taskKey[Seq[File]]("Publish restspec (idl) files from the rest.li server project to the rest.li client project.")
  val restLiSnapshotPublish = taskKey[Seq[File]]("Publish snapshot files from the rest.li server project to the rest.li client project.")
  val restLiCompatMode = settingKey[String]("The compatibility mode to run in, must be one of: equivalent, (the default), backwards, ignore, off")
  val restliRestspecPublishedJsonDir = settingKey[File]("The dir of JSON files published by the restli idl publisher, this is where the checked-in idl should reside")
  val restliSnapshotPublishedJsonDir = settingKey[File]("The dir of JSON files published by the restli snapshot publisher, this is where the checked-in snapshots should reside")
  val restliResourceModelExport = taskKey[IdlAndSnapshotFiles]("Generates JSON files based on the defined Resource classes.")
  val restliRestspecApiName = settingKey[String]("The name of the API for the restli idlgen resource model exporter")
  val restliRestspecResourceProducts = taskKey[Seq[File]]("The products (including jars and class directories) of this project")
  val restliRestspecResourceClasspath = taskKey[Keys.Classpath]("The compile classpath of this project")
  val restliRestspecResourceSourcePaths = taskKey[Seq[String]]("The root source paths of (handwritten) Java restli resources")
  val restliRestspecResourcePackages = settingKey[Seq[String]]("The packages to check for (handwritten) Java restli resources")
  val restliRestspecGeneratedJsonDir = settingKey[File]("The dir of JSON files generated by the restli idlgen resource model exporter")
  val restliSnapshotGeneratedJsonDir = settingKey[File]("The dir of JSON files generated by the restli snapshot resource model exporter")
  val restliRestspecResolverPath = taskKey[String]("List of places to look for pdsc files. Seperated by ':'")
  val restliRestspecResourceAndPdscInfoCache = taskKey[File]("File for caching info about resource class files and pdsc files")
  val restliRestspecJsonInfoCache = taskKey[File]("File for caching info about generated JSON idl files")
  val restliSnapshotJsonInfoCache = taskKey[File]("File for caching info about generated JSON snapshot files")

  //transforms a Project to a RestspecProject if needed, i.e. when you call a method that exists only on RestspecProject
  implicit def projectToRestspecProject(p : Project) = new RestspecProject(p)

  /**
   * Runs the idl and the snapshot generators and returns the generated file references.
   */
  val resourceModelExporter = Def.task {
    val s = streams.value
    val resourceProducts = restliRestspecResourceProducts.value
    val resourcePackages = restliRestspecResourcePackages.value
    val infoCache = restliRestspecResourceAndPdscInfoCache.value
    val resolverPath = restliRestspecResolverPath.value
    val apiName = restliRestspecApiName.value
    val resourceClasspath = restliRestspecResourceClasspath.value
    val resourceSourcePaths = restliRestspecResourceSourcePaths.value
    val snapshotGeneratedJsonDir = restliSnapshotGeneratedJsonDir.value
    val restspecGeneratedJsonDir = restliRestspecGeneratedJsonDir.value

    val (anyFilesChanged, updateCache) = {
      def packageToDir(p: String) = p.replace(".", java.io.File.separator)

      // the class files in each (targetDir, resourcePackage) combination
      val resourceClassFiles = (for {
        targetDir <- resourceProducts
        resourcePackage <- resourcePackages
      } yield {
        ((targetDir / packageToDir(resourcePackage)) ** ClassFileGlobExpr).get
      }).flatten

      // add the pdsc files from the resolver path
      val resolverPaths: Seq[File] = resolverPath.split(java.io.File.pathSeparatorChar).map(new File(_)).filter(_.exists())
      val pdscFiles = resolverPaths.flatMap { path =>
        (path ** PdscFileGlobExpr).get
      }

      prepareCacheUpdate(infoCache, resourceClassFiles ++ pdscFiles, s)
    }

    if (anyFilesChanged) {
      SnapshotCheckerGenerator.runGenerator(resourceProducts, resourcePackages, s, snapshotGeneratedJsonDir,
        resourceClasspath, apiName, resourceSourcePaths, resolverPath)

      IdlCheckerGenerator.runGenerator(resourceProducts, resourcePackages, s, restspecGeneratedJsonDir,
        resourceClasspath, apiName, resourceSourcePaths, resolverPath)

      updateCache() //we only update the cache when we get here, which means we are successful
    } else {
      s.log.info("Cache is up-to-date, skipping idl and snapshot regeneration")
    }
    IdlAndSnapshotFiles(
      (restspecGeneratedJsonDir ** IdlCheckerGenerator.fileGlob).get,
      (snapshotGeneratedJsonDir ** SnapshotCheckerGenerator.fileGlob).get
    )
  }

  val restspecPublisher = Def.task {
    IdlCheckerGenerator.runCompatibilityChecker(
      (restliResourceModelExport in Compile).value.idlFiles,
      restliRestspecPublishedJsonDir.value,
      restLiCompatMode.value,
      restliRestspecResolverPath.value,
      streams.value.log)

    restliRestspecPublishedJsonDir.value.get
  }

  val snapshotPublisher = Def.task {
    SnapshotCheckerGenerator.runCompatibilityChecker(
      (restliResourceModelExport in Compile).value.snapshotFiles,
      restliSnapshotPublishedJsonDir.value,
      restLiCompatMode.value,
      restliRestspecResolverPath.value,
      streams.value.log)

    restliSnapshotPublishedJsonDir.value.get
  }
}

trait CheckerGenerator {
  def name: String
  def fileGlob: String
  protected def checkCompatibility(filePairs: Seq[(java.io.File, java.io.File)], resolverPath: String, compatLevel: CompatibilityLevel, log: Logger): Option[String]
  protected def generate(apiName: String, classpath: Seq[String], resourceSourcePaths: Seq[String], resourcePackages: Seq[String], generatedJsonDir: File, resolverPath: String): GeneratorResult

  def runCompatibilityChecker(jsonFiles: Seq[java.io.File], outdir: java.io.File, compatMode: String, resolverPath: String, log: Logger) = {
    outdir.mkdirs()
    val filePairs = jsonFiles map { f =>
      (f, outdir / f.getName)
    }

    checkCompatibility(filePairs, resolverPath, CompatibilityLevel.valueOf(compatMode.toUpperCase), log) match {
      case Some(message) => {
        log.info(message)
        log.info(s"Publishing $name files to API project ...")
        filePairs foreach { case (src, dest) =>
          IO.copyFile(src, dest)
        }
      }
      case None => {
        log.info(s"$name files are equivalent. No need to publish.")
      }
    }
    outdir.get
  }

  def runGenerator(resourceProducts: Seq[File],
                   resourcePackages: Seq[String],
                   streams: std.TaskStreams[_],
                   generatedJsonDir: File,
                   resourceClasspath: Classpath,
                   apiName: String,
                   resourceSourcePaths: Seq[String],
                   resolverPath: String) {

    generatedJsonDir.mkdirs()

    val previousJsonFiles = (generatedJsonDir ** fileGlob).get
    val cp = resourceClasspath map (_.data.absolutePath)
    val cl = ClasspathUtil.classLoaderFromClasspath(cp, this.getClass.getClassLoader)

    val generatedModel = try {
      ClasspathUtil.withContextClassLoader(cl) {
        generate(apiName, cp, resourceSourcePaths, resourcePackages, generatedJsonDir, resolverPath)
      }
    } catch {
      case e: Throwable =>
        streams.log.error("Running %s exporter for %s: %s".format(name, apiName, e.toString))
        streams.log.error("Resource project products: " + resourceProducts.mkString(", "))
        streams.log.error("Resource classpath: " + resourceClasspath.mkString(", "))
        streams.log.error("Exporter classpath: " + resourceClasspath.mkString(", "))
        streams.log.error("Source paths: " + resourceSourcePaths.mkString(", "))
        streams.log.error("Resource packages: " + resourcePackages.mkString(", "))
        streams.log.error("Generated JSON dir: " + generatedJsonDir)
        streams.log.error("JSON file glob expression: " + fileGlob)
        throw e
    }

    val generatedJsonFiles = generatedModel.getModifiedFiles.asScala.toSeq ++ generatedModel.getTargetFiles.asScala.toSeq

    val staleFiles = previousJsonFiles.sorted.diff(generatedJsonFiles.sorted)
    streams.log.debug("deleting stale files: " + staleFiles)
    IO.delete(staleFiles)
  }

  protected def directionsMessage(compatLevel: CompatibilityLevel): String = {
    s"This check was run on compatibility level ${compatLevel.toString.toLowerCase}.\n"
  } + {
    if(compatLevel == CompatibilityLevel.EQUIVALENT) {
      "You may set compatibility to 'backwards' to the build command to allow backwards compatible changes in interface.\n"
    } else ""
  } + {
    if(compatLevel == CompatibilityLevel.BACKWARDS || compatLevel == CompatibilityLevel.EQUIVALENT) {
      "You may set compatibility to 'ignore' to ignore compatibility errors.\n"
    } else ""
  } + {
     """In SBT, you can change the mode using the 'compatMode' param on the compileRestspec() method in Build.scala.
       |E.g. .compileRestspec(..., compatMode = "backwards")
       |Documentation: https://github.com/linkedin/rest.li/wiki/Resource-Compatibility-Checking
       """.stripMargin
  }
}

object IdlCheckerGenerator extends CheckerGenerator with Restli {
  override def name = "idl"
  override def fileGlob = RestspecJsonFileGlobExpr

  /**
   * Checks each pair of restspec.json files (current, previous) for compatibility.  Throws an exception containing
   * incompatibility details if incompatible changes, for the given mode, were found.
   *
   * @param filePairs (current, previous) file pairs
   * @param compatLevel provides the compatibility mode to use.  Must be one of: "equivalent", "backwards", "ignore", "off"
   * @param log provides a logger
   * @return A error message string if ANY compatibility differences were found (even if compatMode is "ignore"),
   *         always None if compatMode is "off"
   */
  override def checkCompatibility(filePairs: Seq[(java.io.File, java.io.File)], resolverPath: String,
                                  compatLevel: CompatibilityLevel, log: Logger): Option[String] = {
    val idlChecker = new RestLiResourceModelCompatibilityChecker()
    idlChecker.setResolverPath(resolverPath)

    filePairs.map { case (currentFile, previousFile) =>
      idlChecker.check(previousFile.getAbsolutePath, currentFile.getAbsolutePath, compatLevel)
    }

    if (idlChecker.getInfoMap.isEquivalent) {
      None
    } else {
      val allCheckMessage = idlChecker.getInfoMap.createSummary()
      val allCheckMessageWithDirections = allCheckMessage + directionsMessage(compatLevel)

      if (idlChecker.getInfoMap.isCompatible(compatLevel)) {
        Some(allCheckMessageWithDirections)
      } else {
        throw new Exception(allCheckMessageWithDirections)
      }
    }
  }

  override def generate(apiName: String, classpath: Seq[String], resourceSourcePaths: Seq[String],
                        resourcePackages: Seq[String], generatedJsonDir: File, resolverPath: String): GeneratorResult = {
    val restliResourceModelExporter = new RestLiResourceModelExporter()
    restliResourceModelExporter.export(apiName, classpath.toArray, resourceSourcePaths.toArray,
      resourcePackages.toArray, null, generatedJsonDir.getAbsolutePath, List[DocsProvider](new ScalaDocsProvider(classpath.toArray)).asJava)
  }
}

object SnapshotCheckerGenerator extends CheckerGenerator with Restli {
  override def name = "snapshot"
  override def fileGlob = SnapshotJsonFileGlobExpr

  /**
   * Checks each pair of snapshot.json files (current, previous) for compatibility.  Throws an exception containing
   * incompatibility details if incompatible changes, for the given mode, were found.
   *
   * Snapshot files contain idl and pdsc information and are ideal for perform exhaustive compatibility checking
   *
   * @param filePairs (current, previous) file pairs
   * @param compatLevel provides the compatibility mode to use.  Must be one of: "equivalent", "backwards", "ignore", "off"
   * @param log provides a logger
   * @return A error message string if ANY compatibility differences were found (even if compatMode is "ignore"),
   *         always None if compatMode is "off"
   */
  override def checkCompatibility(filePairs: Seq[(java.io.File, java.io.File)], resolverPath: String,
                                  compatLevel: CompatibilityLevel, log: Logger): Option[String] = {
    val snapshotChecker = new RestLiSnapshotCompatibilityChecker()
    snapshotChecker.setResolverPath(resolverPath)

    val compatibilityMap = new CompatibilityInfoMap

    // check compatibility of each set of files
    filePairs.foreach { case (currentFile, previousFile) =>
      val infoMap = snapshotChecker.check(previousFile.getAbsolutePath, currentFile.getAbsolutePath, compatLevel)
      compatibilityMap.addAll(infoMap)
    }

    if (compatibilityMap.isEquivalent) {
      None
    } else {
      val allCheckMessage = compatibilityMap.createSummary()
      val allCheckMessageWithDirections = allCheckMessage + directionsMessage(compatLevel)

      if (compatibilityMap.isCompatible(compatLevel)) {
        Some(allCheckMessageWithDirections)
      } else {
        throw new Exception(allCheckMessageWithDirections)
      }
    }
  }

  override def generate(apiName: String, classpath: Seq[String], resourceSourcePaths: Seq[String],
                        resourcePackages: Seq[String], generatedJsonDir: File, resolverPath: String): GeneratorResult = {
    val restliResourceSnapshotExporter = new RestLiSnapshotExporter()
    restliResourceSnapshotExporter.setResolverPath(resolverPath)
    restliResourceSnapshotExporter.export(apiName, classpath.toArray, resourceSourcePaths.toArray,
      resourcePackages.toArray, null, generatedJsonDir.getAbsolutePath, List[DocsProvider](new ScalaDocsProvider(classpath.toArray)).asJava)
  }
}