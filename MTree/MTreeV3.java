package weka.clusterers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import weka.classifiers.rules.DecisionTableHashKey;
import weka.clusterers.ClusterEvaluation;
import weka.clusterers.CascadeSimpleKMeans;
import weka.clusterers.EM;
import weka.clusterers.CascadeSimpleKMeans;
import weka.clusterers.AbstractClusterer;
import weka.clusterers.Canopy;
import weka.clusterers.Cobweb;
import weka.clusterers.FarthestFirst;
import weka.clusterers.HierarchicalClusterer;
import weka.clusterers.NumberOfClustersRequestable;
import weka.clusterers.RandomizableClusterer;
import weka.clusterers.SimpleKMeans;
import weka.core.Attribute;
import weka.core.Capabilities;
import weka.core.DenseInstance;
import weka.core.DistanceFunction;
import weka.core.EuclideanDistance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.ManhattanDistance;
import weka.core.Option;
import weka.core.SelectedTag;
import weka.core.Tag;
import weka.core.TechnicalInformation;
import weka.core.TechnicalInformationHandler;
import weka.core.Utils;
import weka.core.WeightedInstancesHandler;
import weka.core.Capabilities.Capability;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Center;
import weka.filters.unsupervised.attribute.ReplaceMissingValues;

//import weka.clusterers.

/**
 * <!-- globalinfo-start --> Cluster data using the MTree algorithm
 * <p/>
 * <!-- globalinfo-end -->
 * 
 * <!-- options-start --> Valid options are:
 * <p/>
 * * 
 * <pre>
 * -N &lt;num&gt;
 *  number of clusters.
 *  (default 100).
 * </pre>
 * 
 * 
 * <pre>
 * -M
 *  Replace missing values with mean/mode.
 * </pre>
 * 
 * 
 * <pre>
 * -A &lt;classname and options&gt;
 *  Distance function to be used for instance comparison
 *  (default weka.core.EuclidianDistance)
 * </pre>
 * 
 * <!-- options-end -->
 */ 

class Node implements Serializable {

	/**
	 * A Node represents a cluster
	 */
	private static final long serialVersionUID = 3838507051790773553L;
	public int nrKeys = 0;
	public boolean isLeaf = true;
	public ArrayList <Double> radix; 
	public ArrayList<Node> routes; 
	public ArrayList<Instance> instances;
	public Instance parent;


	public Node(int size, Instances train)
	{
		radix = new ArrayList<Double>(size);  
		routes = new ArrayList<Node>(size);   //the children
		instances = new ArrayList<Instance>(size); 
		nrKeys = 0;
		isLeaf = true;

		for (int i = 0; i < size; i++) {
			this.radix.add(new Double(0.0));
			this.routes.add(new Node());
			Instance newInst = new DenseInstance(train.numAttributes());
			newInst.setDataset(train);
			this.instances.add(newInst);
		}
	}

	public Node() {
		radix = new ArrayList<Double>();  
		routes = new ArrayList<Node>();   
		instances = new ArrayList<Instance>();
		nrKeys = 0;
		isLeaf = true;
	}

}

class InstanceRadix implements Serializable
{
	private static final long serialVersionUID = 3838507051790773554L;
	public Instance center;
	public double radix;
	public boolean isPlaceHolder = false;
}

class PriorityQueueElement
{
	private static final long serialVersionUID = 3838507051790773555L;
	Node node; // a node from the M-tree
	Instance parent; // the parent object of the node in the tree
	double dmin; // the minimum distance from which an object from this node's children can be found from Q
}

class SplitOutput implements Serializable{

	private static final long serialVersionUID = -8395584129162721591L;

	public ArrayList<Node> clusters = new ArrayList<Node>();
	public ArrayList<Instance> centers = new ArrayList<Instance>();
	public ArrayList <Double> radix = new ArrayList<Double>();
}

class MTreeBean implements Serializable{

	/**
	 * The class containing the root node
	 */
	private static final long serialVersionUID = 3498776313092608804L;
	public Node root;


	public MTreeBean()
	{
		root = new Node();
	}	
}

/**
 * Clustering algorithm based on MTree data structure.
 */
public class MTree extends RandomizableClusterer implements
NumberOfClustersRequestable, WeightedInstancesHandler,
TechnicalInformationHandler {
	int index2 = 0;	
	private static Instances train;
	protected DistanceFunction m_DistanceFunction;
	private DecimalFormat Format = new DecimalFormat("#0.00");
	long stopTime = 0;
	long startTime = 0;
	MTreeBean mTree = new MTreeBean();

	/** for serialization. */
	static final long serialVersionUID = -3235809600124455376L;

	/**
	 * replace missing values in training instances.
	 */
	private ReplaceMissingValues m_ReplaceMissingFilter = new ReplaceMissingValues();

	/**
	 * number of clusters to generate.
	 */
	private int m_NumClusters = 100;
	
	/**
	 * number of nodes for each split
	 * -1 means chosen by the split policy
	 */
	private int m_SplitNumber = -1;
	
	/**
	 * holds the cluster centroids.
	 */
	private Instances m_ClusterCentroids;

	/**
	 * Replace missing values globally?
	 */
	private boolean m_dontReplaceMissing = true;
	
	/**
	 * Use KMeans++ seed optimisation?
	 */
	private boolean m_SeedOptimisation = true;
	
	public static final int CANOPY = 0;
	public static final int CASCADE_KMEANS = 1;
	public static final int COBWEB = 2;
	public static final int FARTHEST_FIRST = 3;
	public static final int HIERARCHICAL_CLUSTERER = 4;
	
	public static final Tag[] TAGS_SELECTION = { new Tag(CANOPY, "Canopy"),
		    new Tag(CASCADE_KMEANS, "cascade_k-means"), new Tag(COBWEB, "CobWeb"),
		    new Tag(FARTHEST_FIRST, "Farthest first"), new Tag(HIERARCHICAL_CLUSTERER, "Hierarchical Clusterer") };
	
	protected int m_splitPolicy = CANOPY;
	protected Clusterer split_policies[]= {new Canopy(), new CascadeSimpleKMeans(), new Cobweb(), new FarthestFirst(), new HierarchicalClusterer()};
	
	protected double m_CobWebAcuity = 0.1;
	protected double m_CobWebCutoff = 1;
	
	public static final int EuclideanDistance = 0;
	public static final int ChebyshevDistance = 1;
	public static final int FilteredDistance = 2;
	public static final int ManhattanDistance = 3;
	public static final int MinkowskiDistance = 4;
	
	public static final Tag[] DISTANCE_SELECTION = { new Tag(EuclideanDistance, "EuclideanDistance"),
		    new Tag(ChebyshevDistance, "ChebyshevDistance"), new Tag(FilteredDistance, "FilteredDistance"),
		    new Tag(ManhattanDistance, "ManhattanDistance"), new Tag(MinkowskiDistance, "MinkowskiDistance") };
	
	 protected int m_DistanceFunctionID = EuclideanDistance;
	
	/**
	 * The number of instances in each cluster.
	 */
	private int[] m_ClusterSizes;// = new int[10000];

	/** 
	 * Holds the squared errors for all clusters.
	 */
	private double[] m_squaredErrors;

	/**
	 * Preserve order of instances.
	 */
	private boolean m_PreserveOrder = true;

	/**
	 * Assignments obtained.
	 */
	protected int[] m_Assignments = null;

	/** whether to use fast calculation of distances (using a cut-off). */
	protected boolean m_FastDistanceCalc = false;

	protected int m_executionSlots = 1;

	/** For parallel execution mode */
	protected transient ExecutorService m_executorPool;

	public int numberOfIterations = 0;
	public int INFINITY = 10000000;
	public ArrayList<Instance> rangeQueryInstances = new ArrayList<Instance>();
	public Instances testCentroids = null;

	public MTree() { 

		super();

		m_SeedDefault = 0;
		m_SplitNumber = -1;
		setSeed(m_SeedDefault);
		try {
			setNumClusters(10);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		setDistanceFunction(new SelectedTag(MTree.EuclideanDistance, MTree.DISTANCE_SELECTION));
		setSplitPolicy(new SelectedTag(MTree.CANOPY, MTree.TAGS_SELECTION));
	}


	/**
	 * Returns a string describing this clusterer
	 * 
	 * @return a description of the evaluator suitable for displaying in the
	 *         explorer/experimenter gui
	 */
	public String globalInfo() {
		return "Cluster data using the MTree algorithm. Can use either "
				+ " the Euclidean distance (default) or the Manhattan distance."
				+ " If the Manhattan distance is used, then centroids are computed "
				+ " as the component-wise median rather than mean." +
				"\n article{DBLP:journals/informaticaSI/MihaescuB12" +
				"author    = {Marian Cristian Mihaescu Dumitru Dan Burdescu}" +
				"title     = {Using M Tree Data Structure as Unsupervised Classification Method}," +
				"journal   = {Informatica (Slovenia)}," +
				"volume    = {36}," +
				"number    = {2}," +
				"year      = {2012}," +
				"pages     = {153-160}," +
				"ee        = {http://www.informatica.si//PDF//36-2/05_Mihaescu\\%20-\\%20Using\\%20M\\%20Tree\\%20Data\\%20Structure\\%20as\\%20Unsupervised\\%20Classification\\%20Method.pdf}," +
				"bibsource = {DBLP, http://dblp.uni-trier.de}}";
	}

	/**
	 *
	 * @return      the capabilities of this clusterer
	 */
	public Capabilities getCapabilities() {

		Capabilities result = super.getCapabilities();
		result.disableAll();
		result.enable(Capability.NO_CLASS);

		// attributes
		result.enable(Capability.NUMERIC_ATTRIBUTES);
		result.enable(Capability.MISSING_VALUES);
		result.enable(Capability.NOMINAL_ATTRIBUTES);
		
		return result;
	}

	/**
	 * Returns an enumeration describing the available options.
	 * 
	 * @return an enumeration of all the available options.
	 */
	@Override
	public Enumeration listOptions() {
		Vector<Option> result = new Vector<Option>();

		result.addElement(new Option("\tnumber of clusters.\n" + "\t(default 10).",
				"N", 1, "-N <num>"));
		
		result.addElement(new Option("\trandom number seed.\n (default 10)"
			     , "S", 1, "-S <num>"));
		
		result.addElement(new Option(
			      "\tSplit policy to use.\n\t0 = Canopy, 1 = CascadeSimpleKMeans, "
			        + "2 = CobWeb, 3 = farthest first, 4 = HierarchicalClusterer.\n\t(default = 0)", "split", 1,
			      "-split"));
		
		result.addElement(new Option("\tnumber to split a node.\n (default -1)",
				"Nsplit", -1, "-NSplit <num>"));

		result.add(new Option("\tDistance function to use.\n"
				+ "\t(default: weka.core.EuclideanDistance)\t0 = EuclideanDistance, 1 = ChebyshevDistance, "
				+ "2 = FilteredDistance, 3 = ManhattanDistance, 4 = MinkowskiDistance", "A", 1,
				"-A <num>"));

		Enumeration en = super.listOptions();
		while (en.hasMoreElements()) {
			result.addElement((Option) en.nextElement());
		}

		return result.elements();
	}
	
	public void setOptions (String[] options)
		    throws Exception {

		    String optionString = Utils.getOption('N', options);

		    if (optionString.length() != 0) {
		      setNumClusters(Integer.parseInt(optionString));
		    }

		    optionString = Utils.getOption('S', options);
		    
		    if (optionString.length() != 0) {
		      setSeed(Integer.parseInt(optionString));
		    }
		    
		    optionString = Utils.getOption("split", options);
		    
		    if (optionString.length() != 0) {
		      setSplitPolicy(new SelectedTag(Integer.parseInt(optionString), MTree.TAGS_SELECTION));
		    }
		    
		    optionString = Utils.getOption("Nsplit", options);
		    
		    if (optionString.length() != 0) {
		      setSplitNumber(Integer.parseInt(optionString));
		    }
		    
		    optionString = Utils.getOption("A", options);
		    
		    if (optionString.length() != 0) {
		      setDistanceFunction(new SelectedTag(Integer.parseInt(optionString), MTree.DISTANCE_SELECTION));
		    }
		  }
	
	public String[] getOptions () {
	    String[] options = new String[10];
	    int current = 0;
	    
	    options[current++] = "-N";
	    options[current++] = "" + getNumClusters();
	    options[current++] = "-S";
	    options[current++] = "" + getSeed();
	    options[current++] = "-split";
	    options[current++] = "" + getSplitPolicy();
	    options[current++] = "-Nsplit";
	    options[current++] = "" + getSplitNumber();
	    options[current++] = "-A";
	    options[current++] = "" + m_DistanceFunctionID;
	    
	    while (current < options.length) {
	      options[current++] = "";
	    }

	    return  options;
	  }

	private int getSplitNumber() {
		return m_SplitNumber;
	}


	public void mTreeInsert(MTreeBean tree, Instance inst) {
		/**
		 * we will perform the split for the root node if the root is a Leaf and
		 * splitPolicy algorithm decides to split the node in at least 2 other nodes.
		 * The number of nodes in which we can split the root must be less or equal
		 * than MaxRootSize  
		 */
		if(tree.root.isLeaf && tree.root.nrKeys > 0 && tree.root.instances.size() > 0) {

			ClusterEvaluation eval = voteKClustering(tree.root);
			if(IsClusterEvaluationValid(eval)) {
				int nrClustersObtained = eval.getNumClusters();
				if(nrClustersObtained > 1) //we must split the node
				{
					Node newRoot = new Node(m_NumClusters, train);

					newRoot.nrKeys = 0;
					newRoot.isLeaf = false;

					SplitOutput splitOutput;
					int numberOfClustersAfterSplit = eval.getNumClusters();

					if(numberOfClustersAfterSplit > m_NumClusters) {
						numberOfClustersAfterSplit = m_NumClusters;
						splitOutput = splitNode(tree.root, numberOfClustersAfterSplit);
					}
					else
						splitOutput = splitNode(tree.root, eval);

					for(int index = 0; index < splitOutput.clusters.size(); index++)				
					{
						newRoot.routes.add(index, splitOutput.clusters.get(index));
						newRoot.instances.add(index, splitOutput.centers.get(index));
						newRoot.radix.add(index, splitOutput.radix.get(index));

						newRoot.nrKeys++;
					}

					tree.root = newRoot;
				}
			}
		}
		m_Node_Insert_Nonfull(tree.root, inst);	
	}

	public void m_Node_Insert_Nonfull(Node node, Instance inst) {

		SplitOutput splitOut;
		while (!node.isLeaf){
			int i,idx = 0;
			Double d = Double.MAX_VALUE;
			Double min = Double.MAX_VALUE;	

			for (i = 0; i < node.nrKeys; i++) {
				if (node.instances.get(i) == null)
					node.instances.set(i, new DenseInstance(train.numAttributes()));

				Instance indexInstance = node.instances.get(i);
				d = m_DistanceFunction.distance(inst, indexInstance);

				if (d < min){
					min = d;
					idx = i;
				}
			}
		    

			if  (node.nrKeys < m_NumClusters && node.nrKeys > 0 && node.instances.size() > 0) { 
				ClusterEvaluation eval = voteKClustering(node.routes.get(idx));
				if(IsClusterEvaluationValid(eval)) {
					int nrClustersObtained = eval.getNumClusters();
					if( (node.routes.get(idx).nrKeys > 0) && (nrClustersObtained > 1) ) {

						if(nrClustersObtained + node.nrKeys - 1 <= m_NumClusters)
							splitOut = splitNode(node.routes.get(idx), eval);
						else
							splitOut = splitNode(node.routes.get(idx), m_NumClusters - node.nrKeys + 1); //minim 2 clustere.

						node.routes.set(idx, splitOut.clusters.get(0));
						node.instances.set(idx, splitOut.centers.get(0));
						node.radix.set(idx, splitOut.radix.get(0));
						
						for(int index = 1; index < splitOut.clusters.size(); index++)				
						{
							node.routes.add(idx + index, splitOut.clusters.get(index));
							node.instances.add(idx + index, splitOut.centers.get(index));
							node.radix.add(idx + index, splitOut.radix.get(index));
							node.nrKeys++;
						}
					}
				}
			}
			node = node.routes.get(idx);
		}

		node.instances.add(node.nrKeys, inst);
		node.nrKeys++;
	}

	public boolean IsClusterEvaluationValid(ClusterEvaluation eval)
	{
		if(eval.getNumClusters() > 0)
		{
			double[] assignments = eval.getClusterAssignments();
			for(int i = 0; i < assignments.length - 1; i++)
				if(assignments[i] != assignments[i+1])
					return true;
		}
		return false;
	}
	private Instances arrayInstanceToInstances(ArrayList<Instance> array) {

		//initialize the instances using first 2 elements of train because
		//Instances doesen't have a more convenient constructor in this situation

		Instances instancesForSplit =  new Instances(train, 0, 0);
		for(int i = 0; i < array.size(); i++)
			instancesForSplit.add(array.get(i));

		return instancesForSplit;
	}

	private ArrayList<Node> getClustersFromVoteK(double[] clusterAssignments, Node parentNode, int nrClusters) {

		ArrayList<Node> clusters = new ArrayList<Node>(nrClusters);

		for(int i = 0; i < nrClusters; i++)
			clusters.add(new Node());

		for(int i = 0; i < parentNode.nrKeys; i++) {

			int index = (int)clusterAssignments[i];
			clusters.get(index).instances.add(parentNode.instances.get(i));

			if (!parentNode.isLeaf)
				clusters.get(index).routes.add(parentNode.routes.get(i));

			clusters.get(index).nrKeys++;
		}

		for(int i = 0; i < clusters.size(); i++)
		{
			if(clusters.get(i).nrKeys < 1)
			{
				clusters.remove(i);
			}
		}
		return clusters;
	}

	/**
	 * This method evaluates a node of the MTree using
	 * splitPolicy algorithm to decide if it can be split or not,
	 * and in the first case which clusters will result
	 * from the split operation. 
	 * 
	 */
	private ClusterEvaluation voteKClustering(Node node) {
		numberOfIterations++;

		VoteK clusterer = new VoteK();
		int prediction = 0;
		ClusterEvaluation eval = new ClusterEvaluation();
		Instances nodeInstances = arrayInstanceToInstances(node.instances);
		
		try {
			if(nodeInstances.numInstances() > 0)
			{
				
				clusterer.setNumClusters(m_NumClusters);
				clusterer.buildClusterer(nodeInstances);
				prediction = clusterer.getPrediction();
				
				if(prediction > 1 && m_SplitNumber != -1)
				{
					prediction = m_SplitNumber;
				}
				
				Clusterer splitPolicyClusterer = getClusterer(prediction);
				
				splitPolicyClusterer.buildClusterer(nodeInstances);
				eval.setClusterer(splitPolicyClusterer);
				eval.evaluateClusterer(nodeInstances);
			}
		}                       
		catch(java.lang.IllegalArgumentException e2)
		{
			e2.printStackTrace();
		} 
		catch (Exception e) {
			e.printStackTrace();
		}
		return eval;
	}

	private Clusterer getClusterer(int prediction) throws Exception {
		
		Clusterer splitPolicy = null;
		
		if(m_splitPolicy == 0) 
		{
			 Canopy splitPolicyClusterer = new Canopy();
			 splitPolicyClusterer.setNumClusters(prediction);
			 splitPolicy = splitPolicyClusterer;
		}
		if(m_splitPolicy == 1)
		{
			CascadeSimpleKMeans splitPolicyClusterer = new CascadeSimpleKMeans();
			splitPolicyClusterer.setMinNumClusters(prediction);
			splitPolicyClusterer.setMaxNumClusters(prediction);
			splitPolicy = splitPolicyClusterer;
		}
		if(m_splitPolicy == 2)
		{
			Cobweb splitPolicyClusterer = new Cobweb();
			splitPolicyClusterer.setAcuity(m_CobWebAcuity);
			splitPolicyClusterer.setCutoff(m_CobWebCutoff);
			splitPolicy = splitPolicyClusterer;
		}
		if(m_splitPolicy == 3)
		{
			FarthestFirst splitPolicyClusterer = new FarthestFirst();
			splitPolicyClusterer.setNumClusters(prediction);
			splitPolicy = splitPolicyClusterer;
		}
		if(m_splitPolicy == 4)
		{
			HierarchicalClusterer splitPolicyClusterer = new HierarchicalClusterer();
			splitPolicyClusterer.setNumClusters(prediction);
			splitPolicy = splitPolicyClusterer;
		}
		return splitPolicy;
	}


	public SplitOutput splitNode(Node node, ClusterEvaluation eval) {
		SplitOutput splitOutput = new SplitOutput();
		try{
			splitOutput.clusters = getClustersFromVoteK(eval.getClusterAssignments(), node, eval.getNumClusters());

			if(splitOutput.clusters.size() > 0)
			{
				for(int i = 0; i < splitOutput.clusters.size(); i++) 
				{
					InstanceRadix instanceRadix = chooseCenter(splitOutput.clusters.get(i));
					splitOutput.centers.add(instanceRadix.center);
					splitOutput.radix.add(instanceRadix.radix);
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}                                 

		return splitOutput;
	}

	public SplitOutput splitNode(Node node,  int nrOfClusters) {

		ClusterEvaluation eval = new ClusterEvaluation();
		                            
		SplitOutput splitOutput = new SplitOutput();
		//obtain the instances as an Instances object from the array of Instances.
		//this operation is needed for the buildClusterer method
		Instances nodeInstances = arrayInstanceToInstances(node.instances);
		try{
			numberOfIterations++;
			
			Clusterer splitPolicyClusterer = split_policies[m_splitPolicy];
			
			splitPolicyClusterer.buildClusterer(nodeInstances);
			eval.setClusterer(splitPolicyClusterer);
			eval.evaluateClusterer(nodeInstances);

			splitOutput.clusters = getClustersFromVoteK(eval.getClusterAssignments(), node, eval.getNumClusters());

			for(int i = 0; i < splitOutput.clusters.size(); i++) {
				InstanceRadix instanceRadix = chooseCenter(splitOutput.clusters.get(i));
				splitOutput.centers.add(instanceRadix.center);
				splitOutput.radix.add(instanceRadix.radix);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}                                 
		return splitOutput;
	}

	public InstanceRadix chooseCenter(Node node) {
		ArrayList<Double> radix = new ArrayList<Double>();

		for (int i = 0; i < node.instances.size(); i++) 
			radix.add(0.0);

		//the biggest distance to the others for each instance 
		for (int i = 0; i < node.instances.size(); i++){
			for (int j = 0; j < node.instances.size(); j++){
				if (m_DistanceFunction.distance(node.instances.get(i),node.instances.get(j)) > radix.get(i))
					radix.set(i, m_DistanceFunction.distance(node.instances.get(i),node.instances.get(j))) ;
			}
		}
		
		
		int indexMinRadix = 0;

		if(radix.size() > 0)
		{
			//minimum from the computed distances
			Double minRadix = radix.get(0);

			for (int i = 0; i < node.nrKeys; i++){
				if (radix.get(i) < minRadix) {
					minRadix = radix.get(i);
					indexMinRadix = i;
				}
			}
		}

		InstanceRadix instanceRadix = new InstanceRadix();
		instanceRadix.center = node.instances.get(indexMinRadix);
		instanceRadix.radix = radix.get(indexMinRadix);

		return instanceRadix;
	}	

	public void m_Tree_Display(MTreeBean tree, BufferedWriter bw) {
		try {						
			m_Node_Print(tree.root,0, bw);
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}				
	}

	public void m_Node_Print(Node node, int level, BufferedWriter writingFile) throws IOException{
		for (int i=0; i < node.nrKeys; i++){
			for (int j=0; j < level; j++){
				writingFile.write("  ");
			}
		}
	}

	public double getSumOfSquaredErrors(MTreeBean mTree) {
		double sse = 0;
		double distance;

		int index1 = 0;
		for(int k = 0; k < mTree.root.nrKeys; k++)
		{
			Instance centroid = mTree.root.instances.get(k);
			if(mTree.root.routes.size() > 0)
			{
				Node node = mTree.root.routes.get(k);

				for(int i = 0; i < node.nrKeys; i++) {
					distance = m_DistanceFunction.distance(centroid, node.instances.get(i));
					if (m_DistanceFunction instanceof EuclideanDistance) 
					{
						sse += (distance * distance);
						index1++;
					}

				}
			}
		}
		return sse;
	}

	/**
	 * 
	 * @return the cluster centroids
	 */
	public Instances getClusterCentroids() {
		return m_ClusterCentroids;
	}

	@Override
	public void buildClusterer(Instances data) throws Exception {

		mTree = new MTreeBean();
		// can clusterer handle the data?
		getCapabilities().testWithFail(data);
		m_ClusterCentroids = new Instances(data, 0 , 1);
		m_ClusterCentroids.delete();
		
		numberOfIterations = 0;
		
		//replace missing values
		m_ReplaceMissingFilter.setInputFormat(data);
		this.train = Filter.useFilter(data, m_ReplaceMissingFilter);
		data = Filter.useFilter(data, m_ReplaceMissingFilter);
		
		String[] opt = getOptions();
		setOptions(opt);
		
		if(m_SeedDefault != 0)
		{
			this.train.randomize(new Random(m_SeedDefault));
		}
		
		
		m_DistanceFunction = setDistance();
		m_DistanceFunction.setInstances(train);
		
		if(m_NumClusters == -1)
		{
			VoteK voteKClusterer = new VoteK();
			voteKClusterer.buildClusterer(data);
			m_NumClusters = voteKClusterer.getPrediction();
		}
		
		startTime = System.currentTimeMillis();
		System.out.println("Insert start");
		
		if(m_SeedOptimisation)
		{
			seedOptimisation(data);		
			for(int i = 0; i < testCentroids.numInstances(); i ++)
			{
				mTreeInsert(mTree, testCentroids.instance(i));
			}
		}
		
		for (int i = 0; i < train.numInstances(); i++){
			mTreeInsert(mTree, train.instance(i));
		}

		System.out.println("Insert end");
		
		clusterData(data);
		
		stopTime = System.currentTimeMillis();
	}

	private void clusterData(Instances data) {
		int[] clusterAssignments = new int[data.numInstances()];
		m_squaredErrors = new double[mTree.root.instances.size()];
		// calculate errors

		for (int i = 0; i < data.numInstances(); i++) {
			clusterAssignments[i] = clusterProcessedInstance(data.instance(i), true, true);
		}
		m_ClusterSizes = new int[mTree.root.nrKeys];

		for(int i = 0; i <  mTree.root.nrKeys; i++) 
		{
			m_ClusterCentroids.add(mTree.root.instances.get(i));
			if(mTree.root.routes.size() > 0)
			{
				m_ClusterSizes[i] = (mTree.root.routes.get(i).nrKeys);
			}
		}

		if (m_PreserveOrder)
			m_Assignments = clusterAssignments;

		m_NumClusters = mTree.root.nrKeys;
		
	}


	/**
	 * return a string describing this clusterer.
	 * 
	 * @return a description of the clusterer as a string
	 */
	@Override
	public String toString() {
		StringBuffer temp = new StringBuffer();
		temp.append("Nr clusters: " + mTree.root.nrKeys + "\n");
		temp.append("Key:");
		m_ClusterSizes = new int[mTree.root.nrKeys];
		for(int i = 0; i <  mTree.root.nrKeys; i++) {
			//m_ClusterCentroids.add(mTree.root.instances.get(i));//set(i, mTree.root.instances.get(i));
			if(mTree.root.routes.size() > 0) {
				m_ClusterSizes[i] = (mTree.root.routes.get(i).nrKeys);

				temp.append(mTree.root.instances.get(i) + " "); //mTree.root.routes.get(i).nrKeys + " ");
			}
		}
		if (m_ClusterCentroids == null) {
			return "No clusterer built yet!";
		}

		temp.append("\nMTrees \n======\n");
		temp.append("\nNumber of instances: " + train.numInstances());
		if (!m_FastDistanceCalc) {
			temp.append("\n");
			if (m_DistanceFunction instanceof EuclideanDistance) {
				temp.append("Within cluster sum of squared errors: "
						+ Utils.sum(m_squaredErrors));
			} else {
				temp.append("Sum of within cluster distances: "
						+ Utils.sum(m_squaredErrors));
			}
		}

		//temp.append("\n Number of iterations:" + numberOfIterations);

		return temp.toString();
	}

	/**
	 * Main method for executing this class.
	 * 
	 * @param args use -h to list all parameters
	 */

	public static void main(String[] args) {
		runClusterer(new MTree(), args);
	}


	@Override
	public TechnicalInformation getTechnicalInformation() {
		TechnicalInformation technicalInformation = new  TechnicalInformation(TechnicalInformation.Type.ARTICLE);
		technicalInformation.setValue(TechnicalInformation.Field.AUTHOR, "Marian Cristian Mihaescu and Dumitru Dan Burdescu");
		technicalInformation.setValue(TechnicalInformation.Field.TITLE, "Using M Tree Data Structure as Unsupervised Classification Method");
		technicalInformation.setValue(TechnicalInformation.Field.VOLUME, "36");
		technicalInformation.setValue(TechnicalInformation.Field.NUMBER, "2");
		technicalInformation.setValue(TechnicalInformation.Field.YEAR, "2012");
		technicalInformation.setValue(TechnicalInformation.Field.PAGES, "153-160");
		technicalInformation.setValue(TechnicalInformation.Field.JOURNAL, "Informatica (Slovenia)");

		return technicalInformation;
	}


	/**
	 * Start the pool of execution threads
	 */
	protected void startExecutorPool() {
		if (m_executorPool != null) {
			m_executorPool.shutdownNow();
		}

		m_executorPool = Executors.newFixedThreadPool(m_executionSlots);
	}

	/**
	 * Classifies a given instance.
	 * 
	 * @param instance the instance to be assigned to a cluster
	 * @return the number of the assigned cluster as an integer if the class is
	 *         enumerated, otherwise the predicted value
	 * @throws Exception if instance could not be classified successfully
	 */
	@Override
	public int clusterInstance(Instance instance) {
		
		/*Instance inst = null;
		if (!m_dontReplaceMissing) {
			m_ReplaceMissingFilter.input(instance);
			m_ReplaceMissingFilter.batchFinished();
			inst = m_ReplaceMissingFilter.output();
		} else {
			inst = instance;
		}*/
		
		m_ReplaceMissingFilter.input(instance);
		Instance inst = m_ReplaceMissingFilter.output();

		return clusterProcessedInstance(inst, false, true);
	}

	/**
	 * clusters an instance that has been through the filters.
	 * 
	 * @param instance the instance to assign a cluster to
	 * @param updateErrors if true, update the within clusters sum of errors
	 * @param useFastDistCalc whether to use the fast distance calculation or not
	 * @return a cluster number
	 */
	private int clusterProcessedInstance(Instance instance, boolean updateErrors,
			boolean useFastDistCalc) {

		double minDist = Integer.MAX_VALUE;
		int bestCluster = 0;

		for (int i = 0; i <  mTree.root.nrKeys && mTree.root.routes.size() > i; i++) {

			if(mTree.root.routes.get(i).instances.contains(instance)) {
				double dist;
				if (useFastDistCalc)
					dist = m_DistanceFunction.distance(instance,
							mTree.root.instances.get(i));
				else
					dist = m_DistanceFunction.distance(instance,
							mTree.root.instances.get(i));
				minDist = dist;
				bestCluster = i;

				break;
			}
			else
			{
				double dist;
				if (useFastDistCalc)
					dist = m_DistanceFunction.distance(instance,
							mTree.root.instances.get(i), minDist);
				else
					dist = m_DistanceFunction.distance(instance,
							mTree.root.instances.get(i));
				if (dist < minDist) {
					minDist = dist;
					bestCluster = i;

				}
			}
		}
		if (updateErrors) {
			if (m_DistanceFunction instanceof EuclideanDistance) {
				// Euclidean distance to Squared Euclidean distance
				minDist *= minDist;
			}
			index2++;
			m_squaredErrors[bestCluster] += minDist;
		}

		return bestCluster;
	}
	
	private void seedOptimisation(Instances data) //KMeans++ seedOptimisation
	{
	      Instances centroids = new Instances(data, 0, 1); 
	      centroids.delete();
	      double[] distToClosestCentroid = new double[data.numInstances()];
	      double[] weightedDistribution  = new double[data.numInstances()];  // cumulative sum of squared distances
	
	      Random gen = new Random();
	      int choose = 0;
	      
	      // first centroid: choose any data point
	      choose = gen.nextInt(data.numInstances() - 1);
	      centroids.add(data.get(choose));
	      
	      for (int c = 1; c < m_NumClusters; c++) {
	        // after first centroid, use a weighted distribution
            // check if the most recently added centroid is closer to any of the points than previously added ones
            for (int p = 0; p < data.numInstances(); p++) 
            {
               // gives chosen points 0 probability of being chosen again -> sampling without replacement
               double tempDistance = Math.pow(m_DistanceFunction.distance(data.get(p), centroids.get(c - 1)), 2); 

               // base case: if we have only chosen one centroid so far, nothing to compare to
               if (c == 1)
               {
                  distToClosestCentroid[p] = tempDistance;
               }
               else 
               { // c != 1 
                  if (tempDistance < distToClosestCentroid[p])
                  {
                	  distToClosestCentroid[p] = tempDistance;
                  }
               }

               if (p == 0)
               {
            	   weightedDistribution[0] = distToClosestCentroid[0];
               }
               else
          	   {
            	   weightedDistribution[p] = weightedDistribution[p-1] + distToClosestCentroid[p];
           	   }

            }

            // choose the next centroid
            double rand = gen.nextDouble();
            for (int j = data.numInstances() - 1; j > 0; j--) 
            {
              
            	if (rand > weightedDistribution[j - 1] / weightedDistribution[data.numInstances() - 1]) 
            	{ 
                  choose = j; // one bigger than the one above
                  break;
               }
               else // Because of invalid dimension errors, we can't make the for loop go to j2 > -1 when we have (j2-1) in the loop.
                  choose = 0;
            }
	         // store the chosen centroid
	         centroids.add(data.get(choose));
	      }
	      testCentroids = centroids;
	}

	public ArrayList<Instance> RangeQuery(int parentClusterNumber, Instance Q, double r)
	{
		rangeQueryInstances.clear();
		Node node = getRoot();
		Instance parent = node.instances.get(parentClusterNumber);
		return RecursiveRangeQuery(parent, node, Q, r);
	}
	/**RangeQuery: searches all the instances that are within a given distance from a given instance */
	public ArrayList<Instance> RecursiveRangeQuery(Instance parent, Node node, Instance Q, double r)
	{
		DistanceFunction distanceFunction = new EuclideanDistance();
		distanceFunction.setInstances(train);
		int i;
		/*if the current node is not a leaf, check to see if you can go downward*/

		if(!node.isLeaf)
		{
			for(i = 0; i < node.nrKeys; i++)
				//if( Math.abs(m_DistanceFunction.distance(Q, node.instances.get(i)) - m_DistanceFunction.distance(parent , node.instances.get(i))) <= r + node.radix.get(i))
				if(distanceFunction.distance(Q, node.instances.get(i)) <= r + node.radix.get(i))
					RecursiveRangeQuery(node.instances.get(i), node.routes.get(i), Q, r);
		}

		else/*else search close objects*/
		{
			for(i = 0; i < node.nrKeys; i++)
				if(Math.abs(distanceFunction.distance(Q, parent) - distanceFunction.distance(parent, node.instances.get(i))) <= r)
					if(distanceFunction.distance(Q, node.instances.get(i)) <= r)
					{
						rangeQueryInstances.add(node.instances.get(i));
					}
		}

		return rangeQueryInstances;
	}

	public double twoFeaturesDistance(Instance a, Instance b)
	{
		double dif1 = (a.value(0) - b.value(0)) * (a.value(0) - b.value(0));
		double dif2 = (a.value(1) - b.value(1)) * (a.value(1) - b.value(1));

		double result = Math.sqrt(dif1 + dif2);

		return result;
	}

	/**RangeQuery: searches all the instances that are within a given distance from a given instance */
	public ArrayList<Instance> CustomRangeQuery(Instance parent, Node node, Instance Q, double r)
	{
		int i;
		/*if the current node is not a leaf, check to see if you can go downward*/

		if(!node.isLeaf)
		{
			for(i = 0; i < node.nrKeys; i++)
				//if( Math.abs(m_DistanceFunction.distance(Q, node.instances.get(i)) - m_DistanceFunction.distance(parent , node.instances.get(i))) <= r + node.radix.get(i))
				if(twoFeaturesDistance(Q, node.instances.get(i)) <= r + node.radix.get(i))
					RecursiveRangeQuery(node.instances.get(i), node.routes.get(i), Q, r);
		}

		else/*else search close objects*/
		{
			for(i = 0; i < node.nrKeys; i++)
				if(Math.abs(twoFeaturesDistance(Q, parent) - twoFeaturesDistance(parent, node.instances.get(i))) <= r)
					if(twoFeaturesDistance(Q, node.instances.get(i)) <= r)
					{
						rangeQueryInstances.add(node.instances.get(i));
					}
		}

		return rangeQueryInstances;
	}
	
	/**Comparator anonymous class implementation used for ordering the KNN Priority Queue*/
	public static Comparator<PriorityQueueElement> distanceComparator = new Comparator<PriorityQueueElement>(){

		@Override
		public int compare(PriorityQueueElement pq1, PriorityQueueElement pq2) {
			if (pq1.dmin < pq2.dmin) return -1;
			if (pq1.dmin > pq2.dmin) return 1;
			return 0;
		}
	};	

	/**Update the KNN Queue deleting the elements with the distance from the given instance greater than the computed one*/
	private void updateQueue(Queue<PriorityQueueElement> kNNPriorityQueue, double distance)
	{
		for(PriorityQueueElement node : kNNPriorityQueue)
		{
			if(node.dmin > distance)
				kNNPriorityQueue.remove(node);
		}
	}

	/**kNN: finds the first k elements from the tree that are the closest
	 *to a given object Q; the points are stored in array NNArrayElements*/
	public ArrayList<Instance> kNN(Instance Q, int k)
	{

		Node root = this.getRoot();
		Queue<PriorityQueueElement> kNNPriorityQueue = new PriorityQueue(k, distanceComparator);
		PriorityQueueElement node, nextNode;

		/* add the root as the first node of PR */
		node = new PriorityQueueElement();
		node.node = root;
		node.parent = null;
		node.dmin = 0;
		kNNPriorityQueue.add(node);

		ArrayList<InstanceRadix> NNArrayElements = new ArrayList<InstanceRadix>(k);
		/* initialise the NN array */
		for(int i = 0; i < k; i++)
		{
			InstanceRadix ir = new InstanceRadix();
			ir.radix = INFINITY;
			NNArrayElements.add(ir);
		}

		/* find the neighbors */
		while(!kNNPriorityQueue.isEmpty())
		{
			nextNode = kNNPriorityQueue.poll();
			if(nextNode.parent == null)
				kNNNodeSearch(null/*nextNode.parent*/, nextNode.node, Q, k, kNNPriorityQueue, NNArrayElements);
			else
				kNNNodeSearch(nextNode.parent, nextNode.node, Q, k, kNNPriorityQueue, NNArrayElements);
		}

		ArrayList<Instance> nnInstances = new ArrayList<Instance>();
		for(int i = 0; i < NNArrayElements.size(); i++)
		{
			nnInstances.add(NNArrayElements.get(i).center);
		}
		return nnInstances;
	}

	/**kNNNodeSearch: finds the first k elements from the tree that are the closest to a given object Q;
	 the points are stored in array NNArrayElements*/
	void kNNNodeSearch(Instance parent, Node root, Instance Q, int k, Queue<PriorityQueueElement> priorityQueue, ArrayList<InstanceRadix> NNArrayElements)
	{
		PriorityQueueElement node = new PriorityQueueElement();
		double dmin, dmax;

		int i;

		/* for internal nodes */
		if(!root.isLeaf)
		{
			for(i = 0; i < root.nrKeys; i++)
				if((parent == null) ||(parent != null && Math.abs(m_DistanceFunction.distance(parent, Q) - m_DistanceFunction.distance(root.instances.get(i), parent)) <= root.radix.get(i) + NNArrayElements.get(NNArrayElements.size() - 1).radix))
				{
					//distance between Q and the closest point of the cluster
					dmin = Math.max(m_DistanceFunction.distance(root.instances.get(i), Q) - root.radix.get(i), 0);
					if(dmin < NNArrayElements.get(NNArrayElements.size() -1).radix)
					{
						/* insert active sub-trees in PR*/
						node.node = root.routes.get(i);
						node.parent = root.instances.get(i);
						node.dmin = dmin;
						priorityQueue.add(node);

						//distance between Q and the farest point in the cluster 
						dmax = m_DistanceFunction.distance(root.instances.get(i), Q) + root.radix.get(i);

						/* update the NN array */
						if(dmax < NNArrayElements.get(NNArrayElements.size() - 1).radix)
						{
							InstanceRadix e = new InstanceRadix();
							e.radix = dmax;
							e.center = root.instances.get(i);
							e.isPlaceHolder = true;
							/* insert e in the array;
			   				   e is only a placeholder for
			   				   its descendants from leaf nodes;
							 */

							NNUpdate(NNArrayElements,k,e,parent);
							/* update the queue */
							updateQueue(priorityQueue, NNArrayElements.get(NNArrayElements.size() - 1).radix);
						}
					}
				}
		}
		/* for leaf nodes */
		else
		{
			for(i = 0; i < root.nrKeys; i++)
				if(Math.abs(m_DistanceFunction.distance(parent, Q) - m_DistanceFunction.distance(root.instances.get(i), parent)) < NNArrayElements.get(NNArrayElements.size() - 1).radix)
				if(m_DistanceFunction.distance(root.instances.get(i), Q) < NNArrayElements.get(NNArrayElements.size() - 1).radix)
				{
					InstanceRadix e = new InstanceRadix();
					e.radix = m_DistanceFunction.distance(root.instances.get(i), Q);
					e.center = root.instances.get(i);
					/* insert the actual points in the array and remove placeholders*/
					NNUpdate(NNArrayElements,k,e,parent);
					/* update the queue */
					updateQueue(priorityQueue,NNArrayElements.get(NNArrayElements.size() - 1).radix);
				}
		}
	}


	/**NNUpdate: insert the found element, e, whose parent is parent, in the NN array*/
	void NNUpdate(ArrayList<InstanceRadix> NNArrayElements , int k, InstanceRadix e, Instance parent)
	{
		int i,j;
		i=0;

		/* find the correct position of e */
		while(i < NNArrayElements.size() - 1 && NNArrayElements.get(i).radix < e.radix)
			i++;

		boolean found =  false;
		for(int l = 0;  l < NNArrayElements.size(); l++)
		{
			if(NNArrayElements.get(l).hashCode() == e.hashCode() || e.radix == NNArrayElements.get(l).radix)
				found = true;
		}
		if(i != NNArrayElements.size() - 1 && !NNArrayElements.contains(e) && !found)
		{
			/* shift the elements after e with
			   one position to the right
			 */
			NNArrayElements.add(i, e);
			NNArrayElements.remove(NNArrayElements.size() - 1);

		}
		i=0;
		/* if there were a placeholder for e (one ancestor)*/
		while(i != NNArrayElements.size() - 1)
		{
			if(NNArrayElements.get(i).center == parent && NNArrayElements.get(i).isPlaceHolder)
			{
				//remove it
				NNArrayElements.remove(i);
				break;
			}
			i++;
		}

	}

	/**
	 * Gets the assignments for each instance.
	 * 
	 * @return Array of indexes of the centroid assigned to each instance
	 * @throws Exception if order of instances wasn't preserved or no assignments
	 *           were made
	 */
	public int[] getAssignments() throws Exception {
		if (!m_PreserveOrder) {
			throw new Exception(
					"The assignments are only available when order of instances is preserved (-O)");
		}
		if (m_Assignments == null) {
			throw new Exception("No assignments made.");
		}
		return m_Assignments;
	}


	/**
	 * set the number of clusters to generate.
	 * 
	 * @param n the number of clusters to generate
	 * @throws Exception if number of clusters is negative
	 */
	@Override
	public void setNumClusters(int n) throws Exception {
		if (n <= 0 && n != -1) {
			throw new Exception("Number of clusters must be > 0");
		}
		m_NumClusters = n;
	}
	
	public DistanceFunction setDistance()
	{
		DistanceFunction f = new EuclideanDistance();
		if(m_DistanceFunctionID == 0)
		{
			f = new EuclideanDistance();
		}
		if(m_DistanceFunctionID == 1)
		{
			f = new weka.core.ChebyshevDistance();
		}
		if(m_DistanceFunctionID == 2)
		{
			f = new weka.core.FilteredDistance();
		}
		if(m_DistanceFunctionID == 3)
		{
			f = new ManhattanDistance();
		}
		if(m_DistanceFunctionID == 4)
		{
			f = new weka.core.MinkowskiDistance();
		}
		
		return f;
	}
	
	public void setSplitPolicy(SelectedTag method) {
	    if (method.getTags() == TAGS_SELECTION) {
	      m_splitPolicy = method.getSelectedTag().getID();
	    }
	  }
	
	public void setDistanceFunction(SelectedTag method) {
	    if (method.getTags() == DISTANCE_SELECTION) {
		      m_DistanceFunctionID = method.getSelectedTag().getID();
		    }
		  }
	
	public void setSplitNumber(int number)
	{
		m_SplitNumber = number;
	}
	
	public SelectedTag getSplitPolicy() {
	    return new SelectedTag(m_splitPolicy, TAGS_SELECTION);
	  }
	
	/**
	 * gets the number of clusters to generate.
	 * 
	 * @return the number of clusters to generate
	 */
	public int getNumClusters() {
		return m_NumClusters;
	}

	/**
	 *  set the seed of the MTree
	 *  
	 *  @param n the seed
	 *  
	 */
	@Override
	public void setSeed(int n)
	{
		m_SeedDefault = n;
	}

	
	/**
	 * Gets the squared error for all clusters.
	 * 
	 * @return the squared error, NaN if fast distance calculation is used
	 * @see #m_FastDistanceCalc
	 */
	public double getSquaredError() {
		if (m_FastDistanceCalc)
			return Double.NaN;
		else
			return Utils.sum(m_squaredErrors);
	}

	/**
	 * Gets the root of the tree.
	 * @return the root
	 */
	public Node getRoot()
	{
		return mTree.root;
	}

	@Override
	public int numberOfClusters() throws Exception {
		return m_NumClusters;
	}

	/**
	 * Returns the tip text for this property
	 * 
	 * @return tip text for this property suitable for displaying in the
	 *         explorer/experimenter gui
	 */
	public String numClustersTipText() {
		return "set number of clusters";
	}
	/**
	 * Returns the tip text for this property.
	 * 
	 * @return tip text for this property suitable for displaying in the
	 *         explorer/experimenter gui
	 */
	public String distanceFunctionTipText() {
		return "The distance function to use for instances comparison "
				+ "(default: weka.core.EuclideanDistance). ";
	}

	/**
	 * Returns the tip text for this property
	 * 
	 * @return tip text for this property suitable for displaying in the
	 *         explorer/experimenter gui
	 */
	public String dontReplaceMissingValuesTipText() {
		return "Replace missing values globally with mean/mode.";
	}

}
