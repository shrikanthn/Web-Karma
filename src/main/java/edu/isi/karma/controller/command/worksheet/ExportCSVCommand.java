/*******************************************************************************
 * Copyright 2012 University of Southern California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This code was developed by the Information Integration Group as part 
 * of the Karma project at the Information Sciences Institute of the 
 * University of Southern California.  For more information, publications, 
 * and related projects, please see: http://www.isi.edu/integration
 ******************************************************************************/
package edu.isi.karma.controller.command.worksheet;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.icu.text.SimpleDateFormat;

import edu.isi.karma.controller.command.WorksheetCommand;
import edu.isi.karma.controller.update.AbstractUpdate;
import edu.isi.karma.controller.update.ErrorUpdate;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.er.helper.ExportCSVUtil;
import edu.isi.karma.er.helper.TripleStoreUtil;
import edu.isi.karma.kr2rml.ErrorReport;
import edu.isi.karma.kr2rml.KR2RMLWorksheetRDFGenerator;
import edu.isi.karma.kr2rml.mapping.KR2RMLMapping;
import edu.isi.karma.kr2rml.mapping.KR2RMLMappingGenerator;
import edu.isi.karma.modeling.alignment.Alignment;
import edu.isi.karma.modeling.alignment.AlignmentManager;
import edu.isi.karma.rep.Worksheet;
import edu.isi.karma.rep.Workspace;
import edu.isi.karma.view.VWorkspace;
import edu.isi.karma.webserver.ServletContextParameterMap;
import edu.isi.karma.webserver.ServletContextParameterMap.ContextParameter;

/**
@author shri
 */
public class ExportCSVCommand extends WorksheetCommand {

	private final String rootNodeId;
	private String tripleStoreUrl;
	private String graphUrl;
	private String generatedRDFFileName = null;
	private final ArrayList<String> columnList;
	
	private static Logger logger = LoggerFactory.getLogger(ExportCSVCommand.class);
	
	public enum JsonKeys {
		worksheetId, columnList, tripleStoreUrl, graphUrl, rootNodeId, updateType, fileUrl
	}
	
	/**
	 * @param id
	 * @param worksheetId
	 * @param rootNode
	 * @param sparqlUrl
	 * @param graph
	 * @param nodes
	 * */
	protected ExportCSVCommand(String id, String worksheetId, String rootNode, String sparqlUrl, String graph, ArrayList<String> nodes ) {
		super(id, worksheetId);
		this.rootNodeId = rootNode;
		this.tripleStoreUrl = sparqlUrl;
		this.graphUrl = graph;
		this.columnList = nodes;
	}

	@Override
	public String getCommandName() {
		return ExportCSVCommand.class.getSimpleName();
	}

	@Override
	public String getTitle() {
		return "Fetch Columns";
	}

	@Override
	public CommandType getCommandType() {
		return CommandType.notInHistory;
	}

	@Override
	public UpdateContainer doIt(Workspace workspace) {
		Worksheet worksheet = workspace.getWorksheet(worksheetId);
		UpdateContainer uc = new UpdateContainer();
		// first check if current worksheet option is select - if yes, publish this worksheet with a new graphURi and 
		// fetch data from this graph
		if(this.graphUrl.equalsIgnoreCase("000")) {
			uc = generateRDF(workspace);
			if(uc != null) {
				return uc;
			}
		}
		
		// Prepare the model file path and names
		final String csvFileName = workspace.getCommandPreferencesId() + worksheetId + "-" + 
				worksheet.getTitle() +  "-data-csv-export"+".csv"; 
		final String modelFileLocalPath = ServletContextParameterMap.getParameterValue(
				ContextParameter.USER_DIRECTORY_PATH) +  "publish/CSV/" + csvFileName;

		HashMap<String, String> result = 
				ExportCSVUtil.generateCSVFile(workspace, this.worksheetId, this.rootNodeId, this.columnList, this.graphUrl, this.tripleStoreUrl, modelFileLocalPath);

		if(this.generatedRDFFileName != null) {
			// delete the created RDF file
			File f = new File(generatedRDFFileName);
			f.delete();
//			TripleStoreUtil.clearContexts(this.tripleStoreUrl, this.graphUrl);
		}
		
		if (!result.containsKey("Error")) {
			logger.info("Returning Update for csv file name");
			uc = new UpdateContainer();
			uc.add(new AbstractUpdate() {
				public void generateJson(String prefix, PrintWriter pw,	
						VWorkspace vWorkspace) {
					JSONObject outputObject = new JSONObject();
					try {
						outputObject.put(JsonKeys.updateType.name(), "ExportCSVUpdate");
						outputObject.put(JsonKeys.fileUrl.name(), "publish/CSV/" + csvFileName);
						outputObject.put(JsonKeys.worksheetId.name(), worksheetId);
						pw.println(outputObject.toString());
					} catch (JSONException e) {
						logger.error("Error occured while generating JSON!");
					}
				}
			});
			return uc;
		} 

		return new UpdateContainer(new ErrorUpdate("Error occured while generating CSV !"));
		
		

	}

	@Override
	public String getDescription() {
		return null;
	}

	@Override
	public UpdateContainer undoIt(Workspace workspace) {
		return null;
	}

	private UpdateContainer generateRDF(Workspace workspace) {
		
		// Prepare the file path and names
		SimpleDateFormat sdf = new SimpleDateFormat("dd-MMM-yyyy-kkmmssS");
		String ts = sdf.format(Calendar.getInstance().getTime());
		final String rdfFileName = workspace.getCommandPreferencesId() + worksheetId + "-" + ts+ ".ttl";
		generatedRDFFileName = ServletContextParameterMap.getParameterValue(ContextParameter.USER_DIRECTORY_PATH) +  
			"publish/RDF/" + rdfFileName;
		final String graphUri = "http://localhost/"+workspace.getCommandPreferencesId() + "/" + worksheetId + "/" + ts;

		logger.info("Generating RDF for current worksheet - " + rdfFileName);
		
		// Get the alignment for this worksheet
		Alignment alignment = AlignmentManager.Instance().getAlignment(
				AlignmentManager.Instance().constructAlignmentId(workspace.getId(), worksheetId));

		if (alignment == null) {
			logger.info("Alignment is NULL for " + worksheetId);
			return new UpdateContainer(new ErrorUpdate(
					"Please align the worksheet before generating RDF!"));
		}
				
		Worksheet worksheet = workspace.getWorksheet(worksheetId);
		
		// Generate the KR2RML data structures for the RDF generation
		final ErrorReport errorReport = new ErrorReport();
		KR2RMLMappingGenerator mappingGen = new KR2RMLMappingGenerator(workspace, worksheet,
				alignment, worksheet.getSemanticTypes(), "s", graphUri, false, errorReport);
				
		KR2RMLMapping mapping = mappingGen.getKR2RMLMapping();
		logger.debug(mapping.toString());
		
		KR2RMLWorksheetRDFGenerator rdfGen = new KR2RMLWorksheetRDFGenerator(worksheet, 
				workspace.getFactory(), workspace.getOntologyManager(),
				generatedRDFFileName, false, mapping, errorReport);
				
		// Generate the RDF using KR2RML data structures
		try {
			rdfGen.generateRDF(true);
			logger.info("RDF written to file: " + generatedRDFFileName);
		} catch (Exception e1) {
			logger.error("Error occured while generating RDF!", e1);
			return new UpdateContainer(new ErrorUpdate("Error occured while generating RDF: " + e1.getMessage()));
		}
		
		TripleStoreUtil utilObj = new TripleStoreUtil();
		boolean result = utilObj.saveToStore(generatedRDFFileName, utilObj.defaultDataRepoUrl, graphUri, true);
		if(result) {
			logger.info("Saved rdf to store");
			this.graphUrl = "http://localhost/"+ts;
			this.tripleStoreUrl = utilObj.defaultDataRepoUrl;
		} else {
			logger.error("Falied to store rdf to karma_data store");
			return new UpdateContainer(new ErrorUpdate("Error: Failed to store RDF to the triple store"));
		}
		return null;
	}

}
