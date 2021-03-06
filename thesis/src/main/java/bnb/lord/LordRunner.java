package bnb.lord;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.thrift.TProcessor;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;

import bnb.Problem;
import bnb.BnbNode;
import bnb.rpc.LordPublic;
import bnb.rpc.ThriftLord;
import bnb.rpc.LordThriftWrapper;
import bnb.stats.LordJobStats;

public class LordRunner implements LordPublic {
	
	private static final Logger LOG = Logger.getLogger(LordRunner.class);
	
	private int nextJobid;
	private final Map<Integer, LordJobManager> jobMap;
	private final Map<Integer, VassalProxy> vassalMap;
	private final int port;
	
	private TServer server;
	
	private Object waitToRunCondVar = new Object();
	
	public LordRunner(int port) {
		jobMap = new HashMap<Integer, LordJobManager>();
		vassalMap = new HashMap<Integer, VassalProxy>();
		this.port = port;
	}
	
	public void start() {
		startServer(port);
	}
		
	public void registerVassal(VassalProxy proxy, int id) {
		if (vassalMap.containsKey(id)) {
			LOG.warn("vassal already registered with id " + id);
		} else {
			LOG.info("Registering vassal " + id);
			synchronized (vassalMap) {
				vassalMap.put(id, proxy);
			}
		}
	}
	
	public void registerVassal(VassalProxy proxy) throws IOException {
		int id = proxy.getVassalId();
		LOG.info("Registering vassal " + id);
		synchronized (vassalMap) {
			vassalMap.put(id, proxy);
		}
	}
	
	public LordJobStats getStats(int jobId) {
		return jobMap.get(jobId).getStats();
	}
	
	@Override
	public void registerVassal(String hostname, int port, int id) {
		VassalProxy proxy = new VassalProxy(hostname, port, id);
		LOG.info("Registering vassal " + id);
		synchronized(vassalMap) {
			vassalMap.put(id, proxy);
		}
		synchronized(waitToRunCondVar) {
			waitToRunCondVar.notify();
		}
	}
	
	private void startServer(int port) {
		TServerSocket serverSocket;
		try {
			serverSocket = new TServerSocket(port);
			TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverSocket);
			LordThriftWrapper lordThriftWrapper = new LordThriftWrapper(this);
			TProcessor processor = new ThriftLord.Processor<LordThriftWrapper>(lordThriftWrapper);
			args.processor(processor);
//			args.processorFactory(new TProcessorFactory(processor));
			server = new TThreadPoolServer(args);
			Thread serverThread = new Thread("Lord Thrift Server") {
				public void run() {
					server.serve();
				}
			};
			serverThread.start();
		} catch (TTransportException ex) {
			LOG.error("Trouble making server socket", ex);
		}
	}
	
	/**
	 * Runs a job when a required number of vassals have checked in to the lord.
	 * @numVassals
	 * 		the number of unique vassals to wait for to run the job
	 */
	public void runJobWhenEnoughVassals(BnbNode root, Problem spec, double bestCost, int numVassals) {
		synchronized(waitToRunCondVar) {
			while (vassalMap.size() < numVassals) {
				try {
					LOG.info("Waiting for " + (numVassals - vassalMap.size()) + " vassals to register");
					waitToRunCondVar.wait();
				} catch (InterruptedException ex) {	}
			}
		}
		LOG.info("Done waiting for vassals to register");
		runJob(root, spec, bestCost, numVassals, 0);
	}
	
	public void runJob(BnbNode root, Problem spec, double bestCost, int numVassals, int minNodesToSave) {		
		List<VassalProxy> vassals = new LinkedList<VassalProxy>();
		Iterator<VassalProxy> iter = vassalMap.values().iterator();
		for (int i = 0; i < numVassals; i++) {
			vassals.add(iter.next());
		}
		runJob(root, spec, bestCost, vassals, minNodesToSave);
	}
	
	public void runJob(BnbNode root, Problem spec, double bestCost, List<VassalProxy> vassalServers, 
			int minNodesToSave) {
		int jobid = nextJobid++;
		
		LordJobStats stats = new LordJobStats();
		stats.aboutToStart();
		
		for (VassalProxy vassal : vassalServers) {
			try {
				if (!vassalMap.containsKey(vassal.getVassalId())) {
					throw new IllegalArgumentException("Trying to run job with unregistered vassal");
				}
			} catch (IOException ex) {
				//TODO: don't use this runner
				LOG.error("Couldn't reach vassal", ex);
			}
		}
		//TODO: what happens if slots free up during this?
		Starter starter = new Starter();
		//used to be using totalSlots for the last arg, but not for now
		List<BnbNode> startNodes = starter.startEvaluation(spec, bestCost, root, 
				vassalServers.size() + minNodesToSave);
		LOG.info("extra start nodes: " + (startNodes.size() - vassalServers.size()));
		
		LordJobManager jobManager = new LordJobManager(jobid, startNodes, spec, vassalServers, stats);
		jobMap.put(jobid, jobManager);
		for (VassalProxy vassal : vassalServers) {
			List<BnbNode> nodePool = new LinkedList<BnbNode>();
			BnbNode node = startNodes.remove(0);
			nodePool.add(node);
			
			try {
				LOG.info("About to start job " + jobid + " on vassal " + vassal.getVassalIdCache());
				vassal.startJobTasks(nodePool, spec, bestCost, jobid, vassal.getNumSlots());
			} catch (IOException ex) {
				LOG.error("Failed to start job tasks on vassal " + vassal.getVassalIdCache(), ex);
			}
		}
		
		stats.finishedSendingInitialWork();
	}

	@Override
	public void sendBestSolCost(double cost, int jobid, int vassalid) throws IOException {
		VassalProxy vassal = vassalMap.get(vassalid);
		if (vassal == null) {
			LOG.error("Lord couldn't locate vassal with id " + vassalid);
			return;
		}
		LordJobManager jobManager = jobMap.get(jobid);
		if (jobManager == null) {
			LOG.error("Lord couldn't locate job with id " + jobid);
			return;
		}
		jobManager.updateMinCost(cost, vassal);
	}

	@Override
	public List<BnbNode> askForWork(int jobid, int vassalid, double bestCost) {
		LordJobManager jobManager = jobMap.get(jobid);
		//TODO: if jobManager is null we should throw an exception
		VassalProxy vassal = vassalMap.get(vassalid);
		if (vassal == null) {
			LOG.error("Lord couldn't locate vassal with id " + vassalid);
		} else {
			jobManager.updateMinCost(bestCost, vassal);
		}
		return jobManager.askForWork(vassalid);
	}
}
