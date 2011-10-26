package bnb.tsp;

public class TspUtils {

	/**
	 * puts the given city in the given index and updates the indexes
	 */
	public static void placeCity(City[] cities, City city, int index) {
		cities[city.index] = cities[index];
		cities[index] = city;
		cities[city.index].index = city.index;
		cities[index].index = index;
	}
	
	public static int cost3opt(City[] nodes, int index1, int index2, int index3)
	{
		int delta = 0;
		if((index3-index2+nodes.length)%nodes.length < (index1-index2+nodes.length)%nodes.length)
		{
			delta -= nodes[index1].dist(nodes[wrap(nodes, index1+1)]);
			delta += nodes[wrap(nodes, index1+1)].dist(nodes[index2]);
			delta -= nodes[index2].dist(nodes[wrap(nodes, index2-1)]);
			delta += nodes[wrap(nodes, index2-1)].dist(nodes[index3]);
			delta -= nodes[index3].dist(nodes[wrap(nodes, index3-1)]);
			delta += nodes[index1].dist(nodes[wrap(nodes, index3-1)]);
		}
		else
		{
			delta -= nodes[index1].dist(nodes[wrap(nodes, index1+1)]);
			delta += nodes[index3].dist(nodes[wrap(nodes, index2-1)]);
			delta -= nodes[index2].dist(nodes[wrap(nodes, index2-1)]);
			delta += nodes[index2].dist(nodes[wrap(nodes, index1+1)]);
			delta -= nodes[index3].dist(nodes[wrap(nodes, index3+1)]);
			delta += nodes[index1].dist(nodes[wrap(nodes, index3+1)]);
		}
		return delta;
	}
	
	public static int cost2opt(City[] nodes, int index1, int index2)
	{
		//add edge from index1 to predecessor of index2
		//add edge from successor of index1 to index2
		//remove edge from index1 to it's successor
		//remove edge from index2 to it's predecessor
		return nodes[index1].dist(nodes[index2-1]) + nodes[index1+1].dist(nodes[index2]) 
			- nodes[index1].dist(nodes[index1+1]) - nodes[index2].dist(nodes[index2-1]);
	}
	
	public static int wrap(City[] nodes, int index) {
		return (index + nodes.length) % nodes.length;
	}	
}
