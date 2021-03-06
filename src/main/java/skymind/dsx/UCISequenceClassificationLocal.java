package skymind.dsx;

import org.apache.log4j.BasicConfigurator;
import org.datavec.api.records.reader.SequenceRecordReader;
import org.datavec.api.records.reader.impl.csv.CSVSequenceRecordReader;
import org.datavec.api.split.NumberedFileInputSplit;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.datasets.datavec.SequenceRecordReaderDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.stats.StatsListener;
import org.deeplearning4j.ui.storage.InMemoryStatsStorage;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerStandardize;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Created by tomhanlon on 12/4/17.
 */
public class UCISequenceClassificationLocal {

    private static final Logger log = LoggerFactory.getLogger(UCISequenceClassificationLocal.class);

    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();


        SequenceRecordReader trainFeatures = new CSVSequenceRecordReader();


        trainFeatures.initialize(new NumberedFileInputSplit(new File("uci/train/features").getAbsolutePath().toString() + "/%d.csv", 0, 449));
        //trainFeatures.initialize(new NumberedFileInputSplit(new F, 0, 449));
        SequenceRecordReader trainLabels = new CSVSequenceRecordReader();
        trainLabels.initialize(new NumberedFileInputSplit(new File("uci/train/labels").getAbsolutePath().toString() + "/%d.csv", 0, 449));

        int miniBatchSize = 10;
        int numLabelClasses = 6;
        DataSetIterator trainData = new SequenceRecordReaderDataSetIterator(trainFeatures, trainLabels, miniBatchSize, numLabelClasses,
                false, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END);

        /*
            STEP III. Normalizing
            Here we use a standard normalizer that will subtract the mean and divide by the std dev
            ".fit" on data -> collects statistics (mean and std dev)
            ".setPreProcessor" -> allows us to use previously collected statistics to normalize on-the-fly.
            For future reference:
                Example in dl4j-examples with a min max normalizer
         */
        DataNormalization normalizer = new NormalizerStandardize();
        normalizer.fit(trainData);
        trainData.setPreProcessor(normalizer);


        /*
            STEP IV. Set up test data.
            Very important: apply the same normalization to the test and train.
         */
        SequenceRecordReader testFeatures = new CSVSequenceRecordReader();
        //uci/test/features
        testFeatures.initialize(new NumberedFileInputSplit(new File("uci/test/features").getAbsolutePath().toString() + "/%d.csv", 0, 149));
        SequenceRecordReader testLabels = new CSVSequenceRecordReader();
        testLabels.initialize(new NumberedFileInputSplit(new File("uci/test/labels").getAbsolutePath().toString() + "/%d.csv", 0, 149));
        DataSetIterator testData = new SequenceRecordReaderDataSetIterator(testFeatures, testLabels, miniBatchSize, numLabelClasses,
                false, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END);
        testData.setPreProcessor(normalizer);
        DataSet tom = testData.next();
        System.out.println(tom.getFeatures());
        System.out.println("################");
        System.out.println(tom.getFeatures().shapeInfoToString());

        System.out.println("################");
        /*
            STEP V.
            Configure the network and initialize it
            Note that the .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue) is not always required,
                but is a technique that was found to help with this data set
         */
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1)
                .weightInit(WeightInit.XAVIER)
                .updater(new Nesterovs(0.9))
                .learningRate(0.005)
                .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                .gradientNormalizationThreshold(0.5)
                .list()
                .layer(0, new GravesLSTM.Builder().activation(Activation.TANH).nIn(1).nOut(10).build())
                .layer(1, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX).nIn(10).nOut(numLabelClasses).build())
                .pretrain(false).backprop(true).build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();

        /*
            STEP VI. Set up the UI

         */
        UIServer uiServer = UIServer.getInstance();
        StatsStorage statsStorage = new InMemoryStatsStorage();
        net.setListeners(new StatsListener(statsStorage));
        uiServer.attach(statsStorage);


        /*
            STEP VII. Train the network, evaluating the test set performance at each epoch
                      Track the loss function and the weight changes and other metrics in the UI.
                      Open up: http://localhost:9000/
         */
        int nEpochs = 40;
        String str = "Test set evaluation at epoch %d: Accuracy = %.2f, F1 = %.2f";
        for (int i = 0; i < nEpochs; i++) {
            net.fit(trainData);
            Evaluation evaluation = net.evaluate(testData);
            log.info(String.format(str, i, evaluation.accuracy(), evaluation.f1()));
            testData.reset();
            trainData.reset();
        }
        log.info("----- Example Complete -----");
        //File locationToSave = new File("trained_uci_model.zip");

        // boolean save Updater
        //boolean saveUpdater = false;

        // ModelSerializer needs modelname, saveUpdater, Location

        //ModelSerializer.writeModel(net,locationToSave,saveUpdater);
        //ModelSerializer.addNormalizerToModel(locationToSave,normalizer);

    }

}