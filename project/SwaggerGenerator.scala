import java.io.File
import io.swagger.codegen.languages.AkkaScalaClientCodegen
import io.swagger.codegen.{ClientOptInput, ClientOpts, CodegenConfig, DefaultGenerator}
import io.swagger.parser.SwaggerParser
import collection.JavaConversions._

object SwaggerGenerator {
  class CustomAkkaCodegen extends AkkaScalaClientCodegen {
//    sourceFolder = "src/generated/scala"
//    resourcesFolder = "src/generated/resources"
//
//    println(supportingFiles)
  }

  def generate(swaggerFile: File, outputDirectory: File): Seq[File] = {
    val clientOptInput = new ClientOptInput()
    val clientOpts = new ClientOpts()
    val swagger = new SwaggerParser().read(swaggerFile.getAbsolutePath())

    clientOptInput.setConfig(new CustomAkkaCodegen())
    clientOptInput.getConfig().setOutputDir(outputDirectory.getAbsolutePath())
    clientOptInput.getConfig().typeMapping().put("Aspect", "JsObject")
    clientOptInput.getConfig().typeMapping().put("Operation", "JsObject")
    clientOptInput.getConfig().typeMapping().put("object", "JsObject")
    clientOptInput.getConfig().importMapping().put("Aspect", "spray.json.JsObject")
    clientOptInput.getConfig().importMapping().put("Operation", "spray.json.JsObject")
    clientOptInput.getConfig().importMapping().put("JsObject", "spray.json.JsObject")
    clientOptInput.getConfig().importMapping().put("JsonPatch", "gnieh.diffson.sprayJson.JsonPatch")
    clientOptInput.getConfig().importMapping().put("object", "spray.json.JsObject")
    clientOptInput.opts(clientOpts).swagger(swagger)

    val generator = new DefaultGenerator()
    generator.opts(clientOptInput)
    generator.generate().filter(_.getAbsolutePath().endsWith(".scala"))
  }
}