package org.elasticsearch.river.eea_rdf.support;

import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.client.Client;
import static org.elasticsearch.common.xcontent.XContentFactory.*;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.river.eea_rdf.settings.EEASettings;

import org.apache.jena.riot.RDFDataMgr ;
import org.apache.jena.riot.RDFLanguages ;

import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.graph.GraphMaker;
import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.datatypes.RDFDatatype;

import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.lang.StringBuffer;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Byte;
import java.lang.ClassCastException;

public class Harvester implements Runnable {

	private final ESLogger logger = Loggers.getLogger(Harvester.class);

	private List<String> rdfUrls;
	private String rdfEndpoint;
	private String rdfQuery;
	private int rdfQueryType;
	private List<String> rdfPropList;
	private Boolean rdfListType = false;
	private Boolean hasList = false;

	private Client client;
	private String indexName;
	private String typeName;
	private int maxBulkActions;
	private int maxConcurrentRequests;

	private Boolean closed = false;

	public Harvester rdfUrl(String url) {
		url = url.substring(1, url.length() - 1);
		rdfUrls = Arrays.asList(url.split(","));
		return this;
	}

	public Harvester rdfEndpoint(String endpoint) {
		this.rdfEndpoint = endpoint;
		return this;
	}

	public Harvester rdfQuery(String query) {
		this.rdfQuery = query;
		return this;
	}

	public Harvester rdfQueryType(String queryType) {
		if(queryType.equals("select"))
			this.rdfQueryType = 1;
		else
			this.rdfQueryType = 0;
		return this;
	}

	public Harvester rdfPropList(String list) {
		list = list.substring(1, list.length() -1);
		rdfPropList = Arrays.asList(list.split(","));
		if(list.isEmpty())
			rdfPropList.clear();
		else
			hasList = true;
		return this;
	}

	public Harvester rdfListType(String listType) {
		if(listType.equals("white"))
			this.rdfListType = true;
		return this;
	}

	public Harvester client(Client client) {
		this.client = client;
		return this;
	}

	public Harvester index(String indexName) {
		this.indexName = indexName;
		return this;
	}

	public Harvester type(String typeName) {
		this.typeName = typeName;
		return this;
	}

	public Harvester maxBulkActions(int maxBulkActions) {
		this.maxBulkActions = maxBulkActions;
		return this;
	}

	public Harvester maxConcurrentRequests(int maxConcurrentRequests) {
		this.maxConcurrentRequests = maxConcurrentRequests;
		return this;
	}

	public void setClose(Boolean value) {
		this.closed = value;
	}

	@Override
	public void run() {

		logger.info(
				"Starting RDF harvester: endpoint [{}], query [{}]," +
				"URLs [{}], index name [{}], typeName {}",
				rdfEndpoint, rdfQuery, rdfUrls, indexName, typeName);

		while (true) {
			if(this.closed){
				logger.info("Ended harvest for endpoint [{}], query [{}]," +
						"URLs [{}], index name {}, type name {}",
						rdfEndpoint, rdfQuery, rdfUrls, indexName, typeName);
				return;
			}

			/**
			 * Harvest from SPARQL endpoint
			 */
			Query query = QueryFactory.create(rdfQuery);
			QueryExecution qexec = QueryExecutionFactory.sparqlService(
					rdfEndpoint,
					query);
			if(rdfQueryType == 1) {
				try {
					ResultSet results = qexec.execSelect();
					Model sparqlModel = ModelFactory.createDefaultModel();

					Graph graph = sparqlModel.getGraph();

					while(results.hasNext()) {
						QuerySolution sol = results.nextSolution();
						Iterator<String> iterS = sol.varNames();

						/**
						 * Each QuerySolution is a triple
						 */
						try {
							String subject = sol.getResource("s").toString();
							String predicate = sol.getResource("p").toString();
							String object = sol.get("o").toString();

							graph.add(new Triple(
										NodeFactory.createURI(subject),
										NodeFactory.createURI(predicate),
										NodeFactory.createLiteral(object)));

						} catch(NoSuchElementException nsee) {
							logger.info("Could not index [{}] / {}: Query result was" +
									"not a triple",	sol.toString(), nsee.toString());
						}

						BulkRequestBuilder bulkRequest = client.prepareBulk();
						addModelToES(sparqlModel, bulkRequest);
					}
				} catch(Exception e) {
					logger.info("Exception on endpoint stuff [{}]", e.toString());
				} finally { qexec.close();}
			}
			else{
				try{
					Model constructModel = ModelFactory.createDefaultModel();
					qexec.execConstruct(constructModel);

					BulkRequestBuilder bulkRequest = client.prepareBulk();
					addModelToES(constructModel, bulkRequest);

				} catch (Exception e) {
					logger.info("Could not index due to [{}]", e.toString());
				} finally {qexec.close();}
			}

			/**
			 * Harvest from RDF dumps
			 */
			for(String url:rdfUrls) {
				if(url.isEmpty()) continue;

				logger.info("Harvesting url [{}]", url);

				Model model = ModelFactory.createDefaultModel();
				RDFDataMgr.read(model, url.trim(), RDFLanguages.RDFXML);
				BulkRequestBuilder bulkRequest = client.prepareBulk();

				addModelToES(model, bulkRequest);
			}

			closed = true;
		}
	}

	/**
	 * Index all the resources in a Jena Model to ES
	 *
	 * @param model the model to index
	 * @param bulkRequest a BulkRequestBuilder
	 */
	private void addModelToES(Model model, BulkRequestBuilder bulkRequest) {
		HashSet<Property> properties = new HashSet<Property>();
		StmtIterator iter = model.listStatements();

		while(iter.hasNext()) {
			Statement st = iter.nextStatement();
			properties.add(st.getPredicate());

		}

		ResIterator rsiter = model.listSubjects();

		while(rsiter.hasNext()){

			Resource rs = rsiter.nextResource();
			StringBuffer json = new StringBuffer();
			json.append("{");

			for(Property prop: properties) {
				if(hasList && (
						(rdfListType && !rdfPropList.contains(prop.toString())) ||
						(!rdfListType && rdfPropList.contains(prop.toString())))) {
				continue;
			}

				NodeIterator niter = model.listObjectsOfProperty(rs,prop);
				if(niter.hasNext()) {
					StringBuffer result = new StringBuffer();
					result.append("[");

					int count = 0;
					String currValue = "";
					Boolean quote = false;
					while(niter.hasNext()) {
						count++;
						RDFNode n = niter.next();
						quote = false;

						if(n.isLiteral()) {
							Object literalValue = n.asLiteral().getValue();
							try {
								Class literalJavaClass = n.asLiteral()
									.getDatatype()
									.getJavaClass();

								if(literalJavaClass.equals(Boolean.class)
										|| literalJavaClass.equals(Byte.class)
										|| literalJavaClass.equals(Double.class)
										|| literalJavaClass.equals(Float.class)
										|| literalJavaClass.equals(Integer.class)
										|| literalJavaClass.equals(Long.class)
										|| literalJavaClass.equals(Short.class)) {

									currValue += literalValue;
								}	else {
									currValue =	EEASettings.parseForJson(
											n.asLiteral().getLexicalForm());
									quote = true;
								}
							} catch (java.lang.NullPointerException npe) {
								currValue = EEASettings.parseForJson(
										n.asLiteral().getLexicalForm());
								quote = true;
							}

						} else if(n.isResource()) {
							currValue = n.asResource().getURI();
							quote = true;
						}
						if(quote) {
							currValue = "\"" + currValue + "\"";
						}

						result.append(currValue);
						result.append(", ");
					}

					result.setCharAt(result.length()-2, ']');
					if(count == 1) {
						result = new StringBuffer(currValue);
					}

					json.append("\"");
					json.append(prop.toString());
					json.append("\" : ");
					json.append(result.toString());
					json.append(",\n");
				}
			}

			json.setCharAt(json.length() - 2, '}');
			bulkRequest.add(client.prepareIndex(indexName, typeName, rs.toString())
					.setSource(json.toString()));
			BulkResponse bulkResponse = bulkRequest.execute().actionGet();

		}
	}

	@Deprecated
	private void delay(String reason, String url) {
		int time = 1000;
		if(!url.isEmpty()) {
			logger.info("Info: {}, waiting for url [{}] ", reason, url);
		}
		else {
			logger.info("Info: {}", reason);
			time = 2000;
		}

		try {
			Thread.sleep(time);
		} catch (InterruptedException e) {}
	}
}
