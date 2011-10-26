package bnb;

public interface TreeNode {
	/**
	 * Runs bounding a solving on this node.  Returns a list of the child nodes
	 * produced in this way, in order of most preferred.
	 */
	public void evaluate(ProblemSpec spec, double bound);
	
	public boolean isEvaluated();
	
	public TreeNode nextChild();
	
	public boolean hasNextChild();
	
	public boolean isSolution();
	
	public double getCost();
	
	public Solution getSolution();
}
