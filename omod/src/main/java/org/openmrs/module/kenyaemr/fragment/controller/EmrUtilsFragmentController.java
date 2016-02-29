/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.kenyaemr.fragment.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jaxen.JaxenException;
import org.jaxen.XPath;
import org.jaxen.dom4j.Dom4jXPath;
import org.json.simple.JSONObject;
import org.openmrs.Concept;
import org.openmrs.Patient;
import org.openmrs.Relationship;
import org.openmrs.Visit;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.calculation.result.CalculationResult;
import org.openmrs.module.kenyaemr.EmrConstants;
import org.openmrs.module.kenyaemr.calculation.EmrCalculationUtils;
import org.openmrs.module.kenyaemr.regimen.RegimenManager;
import org.openmrs.module.kenyaemr.util.EmrUiUtils;
import org.openmrs.module.kenyaemr.api.KenyaEmrService;
import org.openmrs.module.kenyaemr.calculation.library.hiv.art.InitialArtStartDateCalculation;
import org.openmrs.module.kenyaemr.regimen.RegimenChange;
import org.openmrs.module.kenyaemr.regimen.RegimenChangeHistory;
import org.openmrs.module.kenyaemr.regimen.RegimenDefinition;
import org.openmrs.module.kenyaemr.regimen.RegimenDefinitionGroup;
import org.openmrs.module.kenyaui.KenyaUiUtils;
import org.openmrs.module.kenyaui.annotation.AppAction;
import org.openmrs.module.kenyaui.annotation.PublicAction;
import org.openmrs.ui.framework.SimpleObject;
import org.openmrs.ui.framework.UiUtils;
import org.openmrs.ui.framework.annotation.FragmentParam;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.fragment.action.SuccessResult;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Fragment actions generally useful for KenyaEMR
 */
public class EmrUtilsFragmentController {

	protected static final Log log = LogFactory.getLog(EmrUtilsFragmentController.class);

	/**
	 * Checks if current user session is authenticated
	 * @return simple object {authenticated: true/false}
	 */
	@PublicAction
	public SimpleObject isAuthenticated() {
		return SimpleObject.create("authenticated", Context.isAuthenticated());
	}

	/**
	 * Attempt to authenticate current user session with the given credentials
	 * @param username the username
	 * @param password the password
	 * @return simple object {authenticated: true/false}
	 */
	@PublicAction
	public SimpleObject authenticate(@RequestParam(value = "username", required = false) String username,
									 @RequestParam(value = "password", required = false) String password) {
		try {
			Context.authenticate(username, password);
		} catch (ContextAuthenticationException ex) {
			// do nothing
		}
		return isAuthenticated();
	}

	/**
	 * Gets the next HIV patient number from the generator
	 * @param comment the optional comment
	 * @return simple object { value: identifier value }
	 */
	public SimpleObject nextHivUniquePatientNumber(@RequestParam(required = false, value = "comment") String comment) {
		if (comment == null) {
			comment = "KenyaEMR UI";
		}

		String id = Context.getService(KenyaEmrService.class).getNextHivUniquePatientNumber(comment);
		return SimpleObject.create("value", id);
	}

	/**
	 * Voids the given relationship
	 * @param relationship the relationship
	 * @param reason the reason for voiding
	 * @return the simplified visit
	 */
	public SuccessResult voidRelationship(@RequestParam("relationshipId") Relationship relationship, @RequestParam("reason") String reason) {
		Context.getPersonService().voidRelationship(relationship, reason);
		return new SuccessResult("Relationship voided");
	}

	/**
	 * Voids the given visit
	 * @param visit the visit
	 * @param reason the reason for voiding
	 * @return the simplified visit
	 */
	@AppAction(EmrConstants.APP_CHART)
	public SuccessResult voidVisit(@RequestParam("visitId") Visit visit, @RequestParam("reason") String reason) {
		Context.getVisitService().voidVisit(visit, reason);
		return new SuccessResult("Visit voided");
	}

	/**
	 * Gets the duration since patient started ART
	 * @param patient the patient
	 * @param now the current time reference
	 * @return the regimen and duration
	 */
	public SimpleObject currentArvRegimen(@RequestParam("patientId") Patient patient, @RequestParam("now") Date now, @SpringBean RegimenManager regimenManager, @SpringBean EmrUiUtils kenyaEmrUi, @SpringBean KenyaUiUtils kenyaUi, UiUtils ui) {
		Concept arvs = regimenManager.getMasterSetConcept("ARV");
		RegimenChangeHistory history = RegimenChangeHistory.forPatient(patient, arvs);
		RegimenChange current = history.getLastChangeBeforeDate(now);

		return SimpleObject.create(
				"regimen", current != null ? kenyaEmrUi.formatRegimenShort(current.getStarted(), ui) : null,
				"duration", current != null ? kenyaUi.formatInterval(current.getDate(), now) : null
		);
	}

	/**
	 * Gets the duration since patient started ART
	 * @param patient the patient
	 * @param now the current time reference
	 * @return the duration interval
	 */
	public SimpleObject durationSinceStartArt(@RequestParam("patientId") Patient patient, @RequestParam("now") Date now, @SpringBean KenyaUiUtils kenyaUi) {
		CalculationResult result = EmrCalculationUtils.evaluateForPatient(InitialArtStartDateCalculation.class, null, patient);
		Date artStartDate = result != null ? (Date) result.getValue() : null;

		return SimpleObject.create("duration", artStartDate != null ? kenyaUi.formatInterval(artStartDate, now) : null);
	}

	/**
	 * Calculates an estimated birthdate from an age value
	 * @param now the current time reference
	 * @param age the age
	 * @return the ISO8601 formatted birthdate
	 */
	public SimpleObject birthdateFromAge(@RequestParam(value = "age") String text,
										 @RequestParam(value = "now", required = false) Date now,
										 @SpringBean KenyaUiUtils kenyaui) {
		/*
		Calendar cal = Calendar.getInstance();
		cal.setTime(now != null ? now : new Date());
		cal.add(Calendar.YEAR, -age);
		*/
		
		text = text.toLowerCase();
		String ageStr = text.substring(0, text.length() - 1);
		String type = text.substring(text.length() - 1);
		int age = Integer.parseInt(ageStr);
		
		Calendar cal = Calendar.getInstance();
		if (type.equalsIgnoreCase("y")) {
			cal.add(Calendar.YEAR, -age);
		} else if (type.equalsIgnoreCase("m")) {
			cal.add(Calendar.MONTH, -age);
		} else if (type.equalsIgnoreCase("w")) {
			cal.add(Calendar.WEEK_OF_YEAR, -age);
		} else if (type.equalsIgnoreCase("d")) {
			cal.add(Calendar.DATE, -age);
		}
		return SimpleObject.create("birthdate", kenyaui.formatDateParam(cal.getTime()));
	}
	
	public JSONObject drugConcept(@RequestParam(value= "listOfDrug", required = false) String listOfDrug,
			UiUtils ui, 
			@SpringBean RegimenManager regimenManager,
			@SpringBean EmrUiUtils kenyaUi){
		JSONObject conceptNameJson = new JSONObject();
		String[] parts = listOfDrug.split("+");
		
		for(int i=0;i<parts.length;i++){
			String a=parts[i];	
			Concept c=Context.getConceptService().getConcept(a);
			conceptNameJson.put("drugConceptName", c.getName());
		}
		
		return conceptNameJson;
	}
	
	public JSONObject drugRegimen(@RequestParam(value= "category", required = false) String category,
			UiUtils ui, 
			@SpringBean RegimenManager regimenManager,
			@SpringBean EmrUiUtils kenyaUi){
		category="ARV";
		List<RegimenDefinitionGroup> regimenGroups = regimenManager.getRegimenGroups(category);
		
		List<RegimenDefinition> regimenDefinitions = new ArrayList<RegimenDefinition>();
		for (RegimenDefinitionGroup group : regimenGroups) {
			regimenDefinitions.addAll(group.getRegimens());
		}
		
		JSONObject arvDrugJson = new JSONObject();
		arvDrugJson.put("drugName", kenyaUi.simpleRegimenDefinitions(regimenDefinitions, ui));
		return arvDrugJson;
	}
	
	public JSONObject addressHierarchy(@RequestParam(value= "state", required = false) String state,
			@RequestParam(value= "township", required = false) String township,
			@RequestParam(value= "patientId", required = false) Patient patient,@SpringBean KenyaUiUtils kenyaUi) {
		
        
		
		Map<String,List> townshipMap= new LinkedHashMap<String,List>();
		
		Map<String,List> villageMap= new LinkedHashMap<String,List>();
		
		String[] stateArr = null ;
		
		File addressFile = new File(OpenmrsUtil.getApplicationDataDirectory()
				+ "myanmaraddresshierarchy.xml");
		if (addressFile.exists()) {
			SAXReader reader = new SAXReader();
			Document document = null;
			try {
				document = reader.read(addressFile.toURI().toURL());
			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (DocumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			XPath stateSelector = null;
			try {
				stateSelector = new Dom4jXPath("//country/state");
			} catch (JaxenException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			@SuppressWarnings("rawtypes")
			List stateList = null;
			try {
				stateList = stateSelector.selectNodes(document);
			} catch (JaxenException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			 stateArr = new String[stateList.size()];
			 String[] townshipArr = new String[stateList.size()];

			if (stateList.size() > 0) {
				for (int i = 0; i < stateList.size(); i++) {
					
					List<String> townshipList=new LinkedList<String>();

					stateArr[i] = ((Element) stateList.get(i))
							.attributeValue("name");
					@SuppressWarnings("rawtypes")
					List townshpList = ((Element) stateList.get(i))
							.elements("township");

					String stateName = ((Element) stateList.get(i))
							.attributeValue("name");

					String townshipName = ((Element) townshpList.get(0))
							.attributeValue("name");
					
					townshipArr[i] = ((Element) townshpList.get(0))
							.attributeValue("name") + ",";
					
					//
					townshipList.add(townshipName);
					
					@SuppressWarnings("rawtypes")
					List villageList = ((Element) townshpList.get(0))
							.elements("village");
					//
					List<String> villageList1=new LinkedList<String>();
					for (int k = 0; k < (villageList.size()); k++) {
						
						String villageName = ((Element) villageList.get(k))
								.attributeValue("name");
						//
						villageList1.add(villageName);
						
					}
					villageMap.put(stateName+townshipName, villageList1);

					for (int j = 1; j < (townshpList.size() - 1); j++) {
						
						townshipName = ((Element) townshpList.get(j))
								.attributeValue("name");
						//
						townshipList.add(townshipName);
						
						townshipArr[i] += ((Element) townshpList.get(j))
								.attributeValue("name") + ",";
						villageList = ((Element) townshpList.get(j))
								.elements("village");
						//
						List<String> villageList2=new LinkedList<String>();
						for (int k = 0; k < (villageList.size()); k++) {
							
							String villageName = ((Element) villageList
									.get(k)).attributeValue("name");
							//
							villageList2.add(villageName);
						}
						//
						villageMap.put(stateName+townshipName, villageList2);
					}

					townshipName = ((Element) townshpList.get((townshpList
							.size() - 1))).attributeValue("name");
					//
					townshipList.add(townshipName);
					townshipMap.put(stateName, townshipList);
					
					townshipArr[i] += ((Element) townshpList
							.get((townshpList.size() - 1)))
							.attributeValue("name")
							+ ",";
					villageList = ((Element) townshpList.get((townshpList
							.size() - 1))).elements("village");
					//
					List<String> villageList3=new LinkedList<String>();
					for (int k = 0; k < (villageList.size()); k++) {
						
						String villageName = ((Element) villageList.get(k))
								.attributeValue("name");
						
						//
						villageList3.add(villageName);
					}
					villageMap.put(stateName+townshipName, villageList3);

				}
			}
		} else {
			log.error("address file does not exist");
		}
		
		
		JSONObject stateJson = new JSONObject();
		JSONObject townshipJson = new JSONObject();
		JSONObject villageJson = new JSONObject();
		
		if(patient!= null){
		stateJson.put("selectedState", patient.getPersonAddress().getStateProvince());
		stateJson.put("townshipListForSelectedState", townshipMap.get(patient.getPersonAddress().getStateProvince()));
		stateJson.put("selectedtownship", patient.getPersonAddress().getCountyDistrict());
		stateJson.put("villageListForSelectedTownship", villageMap.get(patient.getPersonAddress().getStateProvince()+patient.getPersonAddress().getCountyDistrict()));
		stateJson.put("selectedvillage", patient.getPersonAddress().getCityVillage());
		}
	
		if(state.equals("")){
			stateJson.put("state", stateArr);
			return stateJson;		
		}
		else{
			if(township.equals("")){
				townshipJson.put("township",townshipMap.get(state));
				return townshipJson;
			}
			else{
				villageJson.put("village",villageMap.get(state+township));
		        return villageJson;
			}
		}
		
	}
}