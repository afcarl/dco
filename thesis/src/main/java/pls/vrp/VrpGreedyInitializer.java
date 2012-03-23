package pls.vrp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class VrpGreedyInitializer {
	private static final double TIME_DIFF_WEIGHT = .4;
	private static final double DISTANCE_WEIGHT = .4;
	private static final double URGENCY_WEIGHT = .2;
	
	private double timeDiffWeight = TIME_DIFF_WEIGHT;
	private double distanceWeight = DISTANCE_WEIGHT;
	private double urgencyWeight = URGENCY_WEIGHT;
	
	public VrpGreedyInitializer(double timeDiffWeight, double distanceWeight, double urgencyWeight) {
		this.timeDiffWeight = timeDiffWeight;
		this.distanceWeight = distanceWeight;
		this.urgencyWeight = urgencyWeight;
	}
	
	/**
	 * Nearest neighbor heuristic from Solomon paper.
	 */
	public VrpSolution nearestNeighborHeuristic(VrpProblem problem) {
		List<List<Integer>> routes = new ArrayList<List<Integer>>();
		Set<Integer> remainingNodes = new HashSet<Integer>();
		for (int i = 0; i < problem.getNumCities(); i++) {
			remainingNodes.add(i);
		}
		
		List<Integer> curRoute = new ArrayList<Integer>();
		routes.add(curRoute);
		int curNodeId = -1;
		double curVisitTime = 0;
		int remCapacity = problem.getVehicleCapacity();
		while (remainingNodes.size() > 0) {
			Number[] ret = findClosest(curNodeId, curVisitTime, remCapacity, remainingNodes, problem);
			int nextNodeId = (Integer)ret[0];
			if (nextNodeId != -1) {
				remainingNodes.remove(nextNodeId);
				curRoute.add(nextNodeId);
				curNodeId = nextNodeId;
				curVisitTime = (Double)ret[1];
				remCapacity -= problem.getDemands()[nextNodeId];
			} else {
				curRoute = new ArrayList<Integer>();
				routes.add(curRoute);
				curVisitTime = 0;
				curNodeId = -1;
				remCapacity = problem.getVehicleCapacity();
			}
		}
		
		return new VrpSolution(routes, problem);
	}
	
	/**
	 * @param curLastId
	 * 		-1 if it's the depot
	 * @param curLastVisitTime
	 * @param curLastServiceTime
	 * @param remainingNodes
	 * @param problem
	 * @return
	 * 		array containing best node id and visit time
	 */
	private Number[] findClosest(int curLastId, double curLastVisitTime, int remCapacity,
			Set<Integer> remainingNodes, VrpProblem problem) {
		
		int[] demands = problem.getDemands();
		int[] windowStartTimes = problem.getWindowStartTimes();
		int[] windowEndTimes = problem.getWindowEndTimes();
		double[][] distances = problem.getDistances();
		double[] distancesFromDepot = problem.getDistancesFromDepot();
		int[] serviceTimes = problem.getServiceTimes();
		int curLastServiceTime = (curLastId == -1) ? 0 : serviceTimes[curLastId];
		
		double bestVal = Integer.MAX_VALUE;
		int bestNodeId = -1;
		double bestNodeVisitTime = -1;
		
		//bj = time when service begins, for depot its 0
		Iterator<Integer> iter = remainingNodes.iterator();
		while (iter.hasNext()) {
			int nodeId = iter.next();
			if (demands[nodeId] > remCapacity) {
				continue;
			}
			
			double distance = (curLastId == -1) ? distancesFromDepot[nodeId] : distances[curLastId][nodeId];
			double minVisitTime = Math.max(windowStartTimes[nodeId], curLastVisitTime + curLastServiceTime + distance);
			if (minVisitTime > windowEndTimes[nodeId]) {
				continue;
			}
			double timeDiff = minVisitTime - (curLastVisitTime + curLastVisitTime);
			double urgency = windowEndTimes[nodeId] - (curLastVisitTime + curLastServiceTime + distance);
			double val = timeDiff * timeDiffWeight + distance * distanceWeight + urgency * urgencyWeight;
			if (val < bestVal) {
				bestVal = val;
				bestNodeId = nodeId;
				bestNodeVisitTime = minVisitTime;
			}
		}
		
		return new Number[] {new Integer(bestNodeId), new Double(bestNodeVisitTime)};
	}
}
