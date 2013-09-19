package scalabuff

import sbt._
import Keys._
import sbt.Fork

import java.io.File

object ScalaBuffPlugin extends Plugin {
  val ScalaBuff = config("scalabuff").hide

  val scalabuff = TaskKey[Seq[File]]("scalabuff", "Generate Scala sources from protocol buffers definitions")
  val scalabuffArgs = SettingKey[Seq[String]]("scalabuff-args", "Extra command line parameters to scalabuff.")
  val scalabuffMain = SettingKey[String]("scalabuff-main", "ScalaBuff main class.")
  val scalabuffVersion =  SettingKey[String]("scalabuff-version", "ScalaBuff version.")
  val scalabuffExternalImportPath = SettingKey[File]("scalabuff-external-import-path", "The path to which scalabuff:library-dependencies are extracted")
  val scalabuffSourceDir = SettingKey[File]("scalabuff-source-dir", "The path with the protobuf files")
  val scalabuffUnpackDependencies = TaskKey[UnpackedDependencies]("scalabuff-unpack-dependencies", "Unpack dependencies.")

  lazy val scalabuffSettings = Seq[Project.Setting[_]](
    scalabuffArgs := Seq(),
    scalabuffMain := "net.sandrogrzicic.scalabuff.compiler.ScalaBuff",
    scalabuffVersion := "1.3.6",
    libraryDependencies <++= (scalabuffVersion in ScalaBuff)(version => 
      Seq(
        "net.sandrogrzicic" %% "scalabuff-compiler" % version % ScalaBuff.name,
        "net.sandrogrzicic" %% "scalabuff-runtime" % version
      )
    ),
    sourceManaged in ScalaBuff <<= (sourceManaged in Compile),
    sourceDirectory in ScalaBuff <<= (sourceDirectory in Compile),

    managedClasspath in ScalaBuff <<= (classpathTypes, update) map { (ct, report) =>
      Classpaths.managedJars(ScalaBuff, ct, report)
    },    

    scalabuff <<= (      
      sourceDirectory in ScalaBuff,
      sourceManaged in ScalaBuff,
      scalabuffMain in ScalaBuff,
      scalabuffArgs in ScalaBuff,
      managedClasspath in ScalaBuff,
      javaHome,
      streams,
      cacheDirectory
    ).map(process),

    scalabuff <<= scalabuff.dependsOn(scalabuffUnpackDependencies in ScalaBuff),

    sourceGenerators in Compile <+= (scalabuff).task,

    scalabuffSourceDir := file((sourceDirectory in ScalaBuff).value + "/protobuf"),

    scalabuffExternalImportPath <<= target(_ / "protobuf_external"),    

    scalabuffUnpackDependencies <<= scalabuffUnpackDependenciesTask,
    
    unmanagedResourceDirectories in Compile += scalabuffSourceDir.value,

    scalabuffArgs += "--generate_json_method",
    scalabuffArgs += "--proto_path=" + scalabuffSourceDir.value.getPath,
    scalabuffArgs += "--proto_path=" + scalabuffExternalImportPath.value.getPath
  )

  case class UnpackedDependencies(dir: File, files: Seq[File])

  private def process(
    source: File,
    sourceManaged: File,
    mainClass: String,
    args: Seq[String],
    classpath: Classpath,
    javaHome: Option[File],
    streams: TaskStreams,
    cache: File
  ): Seq[File] = {
    val input = source / "protobuf"
    if (input.exists) {
      val output = sourceManaged / "scala"
      val cached = FileFunction.cached(cache / "scalabuff", FilesInfo.lastModified, FilesInfo.exists) {
        (in: Set[File]) => {
          IO.delete(output)
          IO.createDirectory(output)
          Fork.java(
            javaHome,
            List(
              "-cp", classpath.map(_.data).mkString(File.pathSeparator), mainClass,
              "--scala_out=" + output.toString
            ) ++ args.toSeq ++ in.toSeq.map(_.toString),
            streams.log
          )
          (output ** ("*.scala")).get.toSet
        }
      }
      cached((input ** "*.proto").get.toSet).toSeq
    } else Nil
  }

  private def unpack(deps: Seq[File], extractTarget: File, log: Logger): Seq[File] = {
    IO.createDirectory(extractTarget)
    deps.flatMap { dep =>
      val seq = IO.unzip(dep, extractTarget, "*.proto").toSeq
      if (!seq.isEmpty) log.debug("Extracted " + seq.mkString(","))
      seq
    }
  }

  private def scalabuffUnpackDependenciesTask = (streams, managedClasspath in ScalaBuff, scalabuffExternalImportPath in ScalaBuff) map {
    (out, deps, extractTarget) =>
      val extractedFiles = unpack(deps.map(_.data), extractTarget, out.log)
      UnpackedDependencies(extractTarget, extractedFiles)
  }
}
