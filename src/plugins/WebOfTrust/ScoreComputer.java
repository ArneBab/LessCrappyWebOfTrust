package plugins.WebOfTrust;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import plugins.WebOfTrust.datamodel.IEdge;
import plugins.WebOfTrust.datamodel.IVertex;

import thomasmarkus.nl.freenet.graphdb.EdgeWithProperty;
import thomasmarkus.nl.freenet.graphdb.H2Graph;

public class ScoreComputer {

	H2Graph graph;

	protected static final int capacities[] = {
		100,// Rank 0 : Own identities
		40,     // Rank 1 : Identities directly trusted by ownIdenties
		16, // Rank 2 : Identities trusted by rank 1 identities
		6,      // So on...
		2,
		1       // Every identity above rank 5 can give 1 point
	};          // Identities with negative score have zero capacity

	public ScoreComputer(H2Graph graph)
	{
		this.graph = graph;
	}

	public void compute(String ownIdentityID) throws SQLException
	{
		//the trust for rank+1 is the sum of (capacity*score) for all the rank peers
		Set<Long> seen_vertices = new HashSet<Long>();
		Set<Long> pool = new HashSet<Long>(graph.getVertexByPropertyValue(IVertex.ID, ownIdentityID));

		Map<Long, Integer> vertexToScore = new HashMap<Long, Integer>();
		Set<Long> localOverride = new HashSet<Long>();
		
		//calculate score per rank
		for(int rank=0; rank < 6; rank++)
		{
			System.out.println("rank: " + rank);

			final Set<Long> next_pool = new HashSet<Long>();

			for(long vertex_id : pool)
			{
				seen_vertices.add(vertex_id);
				
				if (!vertexToScore.containsKey(vertex_id) || vertexToScore.get(vertex_id) > 0)
				{
					List<EdgeWithProperty> edges = graph.getOutgoingEdgesWithProperty(vertex_id, IEdge.SCORE);

					if (rank == 0) {
						vertexToScore.put(vertex_id, 100); //set 100 trust to our own identity
						localOverride.add(vertex_id); //it is not possible to change this trust value anymore, because of locally set value
					}
					
					for(EdgeWithProperty edge : edges)	//gather all connected nodes, filter those already seen
					{
						next_pool.add(edge.vertex_to);
						if (!vertexToScore.containsKey(edge.vertex_to)) vertexToScore.put(edge.vertex_to, 0);

						if (!localOverride.contains(edge.vertex_to)) //only update trust value if it hasn't been overridden by a local trust value
						{
							final int score = Integer.parseInt(edge.value);
							final int updated_score = vertexToScore.get(edge.vertex_to) + (int) Math.round(score*(capacities[rank]/100.0));
							vertexToScore.put(edge.vertex_to, updated_score);
						}

						if (rank == 0)	localOverride.add(edge.vertex_to);
					}
				}
			}

			//normalize score of next pool participants
			for(Long vertex_id : next_pool)	normalize(vertexToScore, vertex_id);
			
			//don't consider identities with trust values of 0 and lower for trust propagation for the next rank
			Iterator<Long> next_pool_iter = next_pool.iterator();
			while(next_pool_iter.hasNext())
			{
				final long vector_id = next_pool_iter.next();
				if (vertexToScore.get(vector_id) <= 0) next_pool_iter.remove();
			}

			//don't re-consider vertices which we've already seen in the previous iteration
			next_pool.removeAll(seen_vertices);
			
			System.out.println();
			//System.out.println("I will consider " + next_pool.size() + " identities for next rank " + (rank+1));
			pool = next_pool; //use the identities from this rank for the next rank
		}

		//update calculated trust values
		int distrusted = 0;
		for(Entry<Long, Integer> pair : vertexToScore.entrySet())
		{
			graph.updateVertexProperty(pair.getKey(), IVertex.TRUST+"."+ownIdentityID,  Long.toString(normalize(vertexToScore, pair.getKey())));
			if (pair.getValue() < 0) distrusted += 1;
		}

		System.out.println("Total: " + vertexToScore.size() + "  distrusted: " + distrusted  + "  filtered total: " + (vertexToScore.size()-distrusted));
	}

	private static long normalize(Map<Long, Integer> vertexToScore, long vertex_id)
	{
		final int final_score = Math.max(Math.min(vertexToScore.get(vertex_id),100), -100);
		vertexToScore.put(vertex_id, final_score);
		return final_score;
	}

}
