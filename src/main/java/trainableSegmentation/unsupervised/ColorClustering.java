package trainableSegmentation.unsupervised;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.Converter;
import ij.process.*;
import org.apache.commons.math3.analysis.function.Abs;
import sun.security.jca.GetInstance;
import trainableSegmentation.FeatureStack;
import trainableSegmentation.FeatureStackArray;
import trainableSegmentation.ReusableDenseInstance;
import weka.clusterers.AbstractClusterer;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;

import java.awt.*;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Random;

/**
 * Comments need to be updated
 */
public class ColorClustering {

    public enum Channel{
        Red("Red"),
        Green("Green"),
        Blue("Blue"),
        Lightness("Lightness"),
        a("a"),
        b("b"),
        Hue("Hue"),
        Saturation("Saturation"),
        Brightness("Brightness");

        private final String label;

        private Channel(String label){
            this.label = label;
        }

        public String toString(){
            return this.label;
        }

        public static String[] getAllLabels(){
            int n = Channel.values().length;
            String[] result = new String[n];
            int i=0;
            for(Channel ch : Channel.values()){
                result[i++] = ch.label;
            }
            return result;
        }

        public static Channel fromLabel(String chLabel){
            if(chLabel != null){
                chLabel = chLabel.toLowerCase();
                for(Channel ch : Channel.values()){
                    String cmp = ch.label.toLowerCase();
                    if(cmp.equals(chLabel)){
                        return ch;
                    }
                }
                throw new IllegalArgumentException("Unable to parse Channel with label: " + chLabel);
            }
            return null;
        }

    };

    private ArrayList<Channel> channels = new ArrayList<Channel>();
    private Instances featuresInstances;
    private ImagePlus image;
    private FeatureStackArray featureStackArray;
    private int numSamples;
    private AbstractClusterer theClusterer;

    /**
     * Creates features based on image and number of samples
     * @param image
     * @param numSamples
     */
    public ColorClustering(ImagePlus image, int numSamples, int numClusters, ArrayList<Channel> selectedChannels){
        for(Channel element: selectedChannels){
            this.channels.add(element);
        }
        this.setImage(image);
        this.setNumSamples(numSamples);
        featureStackArray = new FeatureStackArray(image.getStackSize());
        this.createFeatures();
        PixelClustering pixelClustering = new PixelClustering(this.getFeaturesInstances(),numClusters);
        theClusterer = pixelClustering.getClusterer();
    }

    /**
     * Creates a b features based on RGB image into Lab image, chooses numSamples pixels at random
     */
    public void createFeatures(){
        for(int slice = 1; slice <= image.getStackSize(); ++slice){
            ImageStack stack = new ImageStack(image.getWidth(),image.getHeight());
            ColorSpaceConverter converter = new ColorSpaceConverter();
            ImagePlus lab = converter.RGBToLab( new ImagePlus("RGB", image.getStack().getProcessor(slice)));//Inneficient
            ImagePlus rgb = image.duplicate();//Inneficient
            ImagePlus hsb = image.duplicate();
            ImageConverter ic = new ImageConverter(rgb);
            ImageConverter ic2 = new ImageConverter(hsb);
            ic.convertToRGBStack();
            ic2.convertToHSB();
            for(int i=0;i<channels.size();++i){//Need to add HSB
                switch (channels.get(i)){
                    case Lightness:
                        stack.addSlice("L",lab.getStack().getProcessor(1));
                        break;
                    case a:
                        stack.addSlice("a", lab.getStack().getProcessor(2));
                        break;
                    case b:
                        stack.addSlice("b",lab.getStack().getProcessor(3));
                        break;
                    case Red:
                        stack.addSlice("Red",rgb.getStack().getProcessor(1).convertToFloatProcessor());
                        break;
                    case Green:
                        stack.addSlice("Green",rgb.getStack().getProcessor(2).convertToFloatProcessor());
                        break;
                    case Blue:
                        stack.addSlice("Blue",rgb.getStack().getProcessor(3).convertToFloatProcessor());
                        break;
                    case Hue:
                        stack.addSlice("Hue",hsb.getStack().getProcessor(1).convertToFloatProcessor());
                        break;
                    case Saturation:
                        stack.addSlice("Saturation",hsb.getStack().getProcessor(2).convertToFloatProcessor());
                        break;
                    case Brightness:
                        stack.addSlice("Brightness",hsb.getStack().getProcessor(3).convertToFloatProcessor());
                        break;
                }
            }
            FeatureStack features = new FeatureStack(stack.getWidth(),stack.getHeight(),false);
            features.setStack(stack);

            featureStackArray.set(features,slice-1);
            if( null == featuresInstances )
            {
                IJ.log("Initializing loaded data...");
                // Create instances
                ArrayList<Attribute> attributes = new ArrayList<Attribute>();
                for (int i=1; i<=featureStackArray.get(slice-1).getSize(); i++)
                {
                    String attString = featureStackArray.get(slice-1).getSliceLabel(i);
                    attributes.add( new Attribute( attString ) );
                }

                if( featureStackArray.get(slice-1).useNeighborhood() )
                    for (int i=0; i<8; i++)
                    {
                        //IJ.log("Adding extra attribute original_neighbor_" + (i+1) + "...");
                        attributes.add( new Attribute( new String( "original_neighbor_" + (i+1) ) ) );
                    }
                featuresInstances = new Instances("segment", attributes, 1);
            }
            Random rand = new Random();
            ArrayList<Point> positions = new ArrayList<Point>();
            for(int x=0;x<image.getWidth();++x){
                for(int y=0;y<image.getHeight();++y){
                    positions.add(new Point(x,y));
                }
            }
            Collections.shuffle(positions);
            for(int i=0;i<numSamples;++i){
                featuresInstances.add(featureStackArray.get(slice-1).createInstance(positions.get(i).x,positions.get(i).y));
                //IJ.log("Position:"+positions.get(i).toString()+";"+ featureStackArray.get(slice-1).createInstance(positions.get(i).x,positions.get(i).y).toString());//this values are wrong
                //IJ.log("Added element "+i+" from coordinates "+positions.get(i).x+","+positions.get(i).y);
            }
        }
    }


    public ImagePlus createClusteredImage(){

        int height = image.getHeight();
        int width = image.getWidth();
        int numInstances = height*width;

        ImageStack stack = new ImageStack(image.getWidth(),image.getHeight());
        IJ.log(channels.toString());
        ColorSpaceConverter converter = new ColorSpaceConverter();
        ImageStack clusteringResult = new ImageStack(width,height);
        ImagePlus lab = converter.RGBToLab(new ImagePlus("RGB",image.getStack()));
        ImagePlus rgb = image.duplicate();
        ImagePlus hsb = image.duplicate();
        double clusterArray[] = new double[numInstances];
        ImageConverter ic = new ImageConverter(rgb);
        ImageConverter ic2 = new ImageConverter(hsb);
        ic.convertToRGBStack();
        ic2.convertToHSB();
        for(int i=0;i<channels.size();++i){//Need to add HSB
            switch (channels.get(i)){
                case Lightness:
                    stack.addSlice("L",lab.getStack().getProcessor(1));
                    break;
                case a:
                    stack.addSlice("a", lab.getStack().getProcessor(2));
                    break;
                case b:
                    stack.addSlice("b",lab.getStack().getProcessor(3));
                    break;
                case Red:
                    stack.addSlice("Red", rgb.getStack().getProcessor(1).convertToFloatProcessor());
                    break;
                case Green:
                    stack.addSlice("Green",rgb.getStack().getProcessor(2).convertToFloatProcessor());
                    break;
                case Blue:
                    stack.addSlice("Blue",rgb.getStack().getProcessor(3).convertToFloatProcessor());
                    break;
                case Hue:
                    stack.addSlice("Hue",hsb.getStack().getProcessor(1).convertToFloatProcessor());
                    break;
                case Brightness:
                    stack.addSlice("Brightness",hsb.getStack().getProcessor(2).convertToFloatProcessor());
                    break;
                case Saturation:
                    stack.addSlice("Saturation",hsb.getStack().getProcessor(3).convertToFloatProcessor());
                    break;
            }
        }
        FeatureStack features = new FeatureStack(stack.getWidth(),stack.getHeight(),false);
        features.setStack(stack);
        final double[] values = new double[features.getSize()];
        final ReusableDenseInstance ins = new ReusableDenseInstance(1.0,values);
        ins.setDataset(featuresInstances);
        try {
            IJ.log("Number of clusters: "+theClusterer.numberOfClusters());
        } catch (Exception e) {
            IJ.log("Error when looking for number of clusters");
        }
        for (int x=0;x<width;++x){
            for(int y=0;y<height;++y){
                features.setInstance(x,y,ins,values);
                try {
                    clusterArray[x+y*width]=theClusterer.clusterInstance(ins);
                    //IJ.log(ins.toString());
                    //IJ.log("Coordinates: "+x+","+y+" Cluster: "+clusterArray[x+y*width]);
                }catch (Exception e){
                    IJ.log("Error when applying clusterer to pixel: "+x+","+y);
                }
            }
        }
        clusteringResult.addSlice(new FloatProcessor(width,height,clusterArray));//Byteprocessor
        return new ImagePlus("clustered image", clusteringResult);
    }

    /**
     * Creates arff file
     * @param name name of the file to be created
     */
    public void createFile(String name, Instances theInstances){
        BufferedWriter out = null;
        try{
            out = new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream( name), StandardCharsets.UTF_8 ) );

            final Instances header = new Instances(theInstances, 0);
            out.write(header.toString());

            for(int i = 0; i < theInstances.numInstances(); i++)
            {
                out.write(theInstances.get(i).toString()+"\n");
            }
        }
        catch(Exception e)
        {
            IJ.log("Error: couldn't write instances into .ARFF file.");
            IJ.showMessage("Exception while saving data as ARFF file");
            e.printStackTrace();
        }
        finally{
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        IJ.log("Created file");
    }



    //Getters and setters

    public int getNumSamples() {
        return numSamples;
    }

    public void setNumSamples(int numSamples) {
        this.numSamples = numSamples;
    }

    public ImagePlus getImage() {
        return image;
    }

    public void setImage(ImagePlus image) {
        this.image = image;
    }


    public FeatureStackArray getFeatureStackArray() {
        return featureStackArray;
    }

    public void setFeatureStackArray(FeatureStackArray featureStackArray) {
        this.featureStackArray = featureStackArray;
    }


    public Instances getFeaturesInstances() {
        return featuresInstances;
    }

    public void setFeaturesInstances(Instances featuresInstances) {
        this.featuresInstances = featuresInstances;
    }

}
