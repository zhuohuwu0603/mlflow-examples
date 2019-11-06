package org.andre.mlflow.examples.wine

import java.io.{File,PrintWriter}
import com.beust.jcommander.{JCommander, Parameter}
import org.apache.spark.sql.{SparkSession,DataFrame}
import org.apache.spark.ml.{Pipeline,PipelineModel}
import org.apache.spark.ml.regression.{DecisionTreeRegressor,DecisionTreeRegressionModel}
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.feature.VectorAssembler
import org.mlflow.tracking.{MlflowClient,MlflowClientVersion}
import org.mlflow.api.proto.Service.RunStatus
import org.andre.mlflow.util.{MLflowUtils,MLeapUtils}

/**
 * MLflow DecisionTreeRegressor with wine quality data.
 */
object TrainWine {
  val spark = SparkSession.builder.appName("DecisionTreeRegressionExample").getOrCreate()
  MLflowUtils.showVersions(spark)

  def main(args: Array[String]) {
    new JCommander(opts, args: _*)
    println("Options:")
    println(s"  trackingUri: ${opts.trackingUri}")
    println(s"  token: ${opts.token}")
    println(s"  experimentName: ${opts.experimentName}")
    println(s"  dataPath: ${opts.dataPath}")
    println(s"  modelPath: ${opts.modelPath}")
    println(s"  maxDepth: ${opts.maxDepth}")
    println(s"  maxBins: ${opts.maxBins}")
    println(s"  runOrigin: ${opts.runOrigin}")

    // MLflow - create or get existing experiment
    val client = MLflowUtils.createMlflowClient(opts.trackingUri, opts.token)

    val experimentId = MLflowUtils.getOrCreateExperimentId(client, opts.experimentName)
    println("Experiment ID: "+experimentId)

    // Train model
    train(client, experimentId, opts.modelPath, opts.maxDepth, opts.maxBins, opts.runOrigin, opts.dataPath)
  }

  def train(client: MlflowClient, experimentId: String, modelDir: String, maxDepth: Int, maxBins: Int, runOrigin: String, dataPath: String) {

    // Read data
    val data = Utils.readData(spark, dataPath)

    // Process data
    println("Input data count: "+data.count())
    val columns = data.columns.toList.filter(_ != Utils.colLabel)
    val assembler = new VectorAssembler()
      .setInputCols(columns.toArray)
      .setOutputCol("features")
    val Array(trainingData, testData) = data.randomSplit(Array(0.7, 0.3), 2019)

    // MLflow - create run
    val runInfo = client.createRun(experimentId)
    val runId = runInfo.getRunId()
    println(s"Run ID: $runId")
    println(s"runOrigin: $runOrigin")

    // MLflow - set tags
    client.setTag(runId, "dataPath",dataPath)
    client.setTag(runId, "mlflowVersion",MlflowClientVersion.getClientVersion())
    client.setTag(runId, "mlflow.source.name",MLflowUtils.getSourceName(getClass()))

    // MLflow - log parameters
    val params = Seq(("maxDepth",maxDepth),("maxBins",maxBins),("runOrigin",runOrigin))
    println(s"Params:")
    for (p <- params) {
      println(s"  ${p._1}: ${p._2}")
      client.logParam(runId, p._1,p._2.toString)
    }

    // Create model
    val dt = new DecisionTreeRegressor()
      .setLabelCol(Utils.colLabel)
      .setFeaturesCol(Utils.colFeatures)
      .setMaxDepth(maxDepth)
      .setMaxBins(maxBins)

    // Create pipeline
    val pipeline = new Pipeline().setStages(Array(assembler,dt))

    // Fit model
    val model = pipeline.fit(trainingData)

    // Make predictions
    val predictions = model.transform(testData)
    println("Predictions Schema:")

    // MLflow - log metrics
    val metrics = Seq("rmse","r2", "mae")
    println("Metrics:")
    for (metric <- metrics) {
      val evaluator = new RegressionEvaluator()
        .setLabelCol(Utils.colLabel)
        .setPredictionCol(Utils.colPrediction)
        .setMetricName(metric)
      val v = evaluator.evaluate(predictions)
      println(s"  $metric: $v - isLargerBetter: ${evaluator.isLargerBetter}")
      client.logMetric(runId, metric, v)
    }

    // MLflow - log tree model artifact
    val treeModel = model.stages.last.asInstanceOf[DecisionTreeRegressionModel]
    val path="treeModel.txt"
    new PrintWriter(path) { write(treeModel.toDebugString) ; close }
    client.logArtifact(runId, new File(path),"details")

    // MLflow - Save model in Spark ML and MLeap formats
    logModelAsSparkML(client, runId, modelDir, model)
    logModelAsMLeap(client, runId, modelDir, model, predictions)

    // MLflow - close run
    client.setTerminated(runId, RunStatus.FINISHED, System.currentTimeMillis())
  }

  def logModelAsSparkML(client: MlflowClient, runId: String, modelDir: String, model: PipelineModel) = {
    val modelPath = s"$modelDir/spark-model"
    model.write.overwrite().save(modelPath)
    client.logArtifacts(runId, new File(modelPath), "spark-model")
  }
  
  def logModelAsMLeap(client: MlflowClient, runId: String, modelDir: String, model: PipelineModel, predictions: DataFrame) = {
    val modelPath = new File(s"$modelDir/mleap-model")
    modelPath.mkdir
    MLeapUtils.saveModelAsSparkBundle(s"file:${modelPath.getAbsolutePath}", model, predictions) 
    client.logArtifacts(runId, modelPath, "mleap-model/mleap/model") // Make compatible with MLflow Python mlflow.mleap.log_model
  } 

  object opts {
    @Parameter(names = Array("--trackingUri" ), description = "Tracking Server URI", required=false)
    var trackingUri: String = null

    @Parameter(names = Array("--token" ), description = "REST API token", required=false)
    var token: String = null

    @Parameter(names = Array("--dataPath" ), description = "Data path", required=true)
    var dataPath: String = null

    @Parameter(names = Array("--modelPath" ), description = "Model path", required=true)
    var modelPath: String = null

    @Parameter(names = Array("--maxDepth" ), description = "maxDepth param", required=false)
    var maxDepth: Int = 5 // per doc

    @Parameter(names = Array("--maxBins" ), description = "maxBins param", required=false)
    var maxBins: Int = 32 // per doc

    @Parameter(names = Array("--runOrigin" ), description = "runOrigin tag", required=false)
    var runOrigin = "None"

    @Parameter(names = Array("--experimentName" ), description = "Experiment name", required=false)
    var experimentName = "scala_classic"
  }
}