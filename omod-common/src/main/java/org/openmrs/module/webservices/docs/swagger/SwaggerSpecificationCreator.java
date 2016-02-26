/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.webservices.docs.swagger;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.docs.ResourceDoc;
import org.openmrs.module.webservices.docs.ResourceRepresentation;
import org.openmrs.module.webservices.docs.SearchHandlerDoc;
import org.openmrs.module.webservices.docs.SearchQueryDoc;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.annotation.SubResource;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.SearchHandler;
import org.openmrs.module.webservices.rest.web.resource.api.SearchQuery;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription.Property;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubclassHandler;
import org.openmrs.module.webservices.rest.web.response.ResourceDoesNotSupportOperationException;
import org.openmrs.util.OpenmrsConstants;
import org.springframework.util.ReflectionUtils;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;

public class SwaggerSpecificationCreator {
	
	private SwaggerSpecification swaggerSpecification;
	
	private String baseUrl;
	
	private static List<ResourceDoc> resourceDocList = new ArrayList<ResourceDoc>();
	
	private static List<SearchHandlerDoc> searchHandlerDocs;
	
	PrintStream originalErr;
	
	PrintStream originalOut;
	
	HashMap<Integer, Level> originalLevels = new HashMap<Integer, Level>();
	
	private Map<String, Tag> tags;
	
	public SwaggerSpecificationCreator(String baseUrl) {
		this.swaggerSpecification = new SwaggerSpecification();
		this.baseUrl = baseUrl;
		List<SearchHandler> searchHandlers = Context.getService(RestService.class).getAllSearchHandlers();
		searchHandlerDocs = fillSearchHandlers(searchHandlers, baseUrl);
		tags = new HashMap<String, Tag>();
	}
	
	public String BuildJSON() {
		synchronized (this) {
			
			originalErr = System.err;
			
			toggleLogs(RestConstants.SWAGGER_LOGS_OFF);
			CreateApiDefinition();
			BetterAddPaths();
			//AddPaths();
			createObjectDefinitions();
			//addResourceTags();
			//toggleLogs(RestConstants.SWAGGER_LOGS_ON);
		}
		return createJSON();
	}
	
	private void toggleLogs(boolean targetState) {
		if (Context.getAdministrationService().getGlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME)
		        .equals("true")) {
			if (targetState == RestConstants.SWAGGER_LOGS_OFF) {
				// turn off the log4j loggers
				List<Logger> loggers = Collections.<Logger> list(LogManager.getCurrentLoggers());
				loggers.add(LogManager.getRootLogger());
				for (Logger logger : loggers) {
					originalLevels.put(logger.hashCode(), logger.getLevel());
					logger.setLevel(Level.OFF);
				}
				
				// silence stderr and stdout
				originalErr = System.err;
				System.setErr(new PrintStream(new OutputStream() {
					
					public void write(int b) {
						// noop
					}
				}));
				
				originalOut = System.out;
				System.setOut(new PrintStream(new OutputStream() {
					
					public void write(int b) {
						// noop
					}
				}));
			} else if (targetState == RestConstants.SWAGGER_LOGS_ON) {
				List<Logger> loggers = Collections.<Logger> list(LogManager.getCurrentLoggers());
				loggers.add(LogManager.getRootLogger());
				for (Logger logger : loggers) {
					logger.setLevel(originalLevels.get(logger.hashCode()));
				}
				
				System.setErr(originalErr);
				System.setOut(originalOut);
			}
		}
	}
	
	private void CreateApiDefinition() {
		Info info = new Info();
		// basic info
		info.setVersion(OpenmrsConstants.OPENMRS_VERSION_SHORT);
		info.setTitle("OpenMRS API Docs");
		info.setDescription("OpenMRS RESTful API specification");
		// contact
		info.setContact(new Contact("OpenMRS", "http://openmrs.org"));
		// license
		info.setLicense(new License("MPL-2.0", "http://openmrs.org/license/"));
		swaggerSpecification.setInfo(info);
		List<String> produces = new ArrayList<String>();
		produces.add("application/json");
		produces.add("application/xml");
		List<String> consumes = new ArrayList<String>();
		consumes.add("application/json");
		consumes.add("application/xml");
		swaggerSpecification.setHost(getBaseUrl());
		swaggerSpecification.setBasePath("/" + RestConstants.VERSION_1);
		swaggerSpecification.setProduces(produces);
		swaggerSpecification.setConsumes(consumes);
	}
	
	private boolean TestOperationImplemented(OperationEnum operation, DelegatingResourceHandler<?> resourceHandler) {
		Method method;
		try {
			switch (operation) {
				case get:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "getAll", RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, new RequestContext());
					}
					
					break;
				case getSubresource:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "getAll", String.class,
					    RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID, new RequestContext());
					}
					
					break;
				case getWithUUID:
				case getSubresourceWithUUID:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "getByUniqueId", String.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID);
					}
					
					break;
				case postCreate:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "create", SimpleObject.class,
					    RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						try {
							// to avoid saving data to the database, we pass a null SimpleObject
							method.invoke(resourceHandler, null, new RequestContext());
						}
						catch (ResourceDoesNotSupportOperationException re) {
							return false;
						}
						catch (Exception ee) {
							// if the resource doesn't immediate throw ResourceDoesNotSupportOperationException
							// then we need to check if it's thrown in the save() method
							resourceHandler.save(null);
						}
					}
					
					break;
				case postSubresource:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "create", String.class,
					    SimpleObject.class, RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						try {
							// to avoid saving data to the database, we pass a null SimpleObject
							method.invoke(resourceHandler, null, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID,
							    new RequestContext());
						}
						catch (ResourceDoesNotSupportOperationException re) {
							return false;
						}
						catch (Exception ee) {
							// if the resource doesn't immediate throw ResourceDoesNotSupportOperationException
							// then we need to check if it's thrown in the save() method
							resourceHandler.save(null);
						}
					}
					
					break;
				case postUpdate:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "update", String.class,
					    SimpleObject.class, RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID,
						    buildPOSTUpdateSimpleObject(resourceHandler), new RequestContext());
					}
					
					break;
				case postUpdateSubresouce:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "update", String.class, String.class,
					    SimpleObject.class, RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID,
						    RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID, buildPOSTUpdateSimpleObject(resourceHandler),
						    new RequestContext());
					}
					
					break;
				case delete:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "delete", String.class, String.class,
					    RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID, new String(),
						    new RequestContext());
					}
					
					break;
				case deleteSubresource:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "delete", String.class, String.class,
					    String.class, RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID,
						    RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID, new String(), new RequestContext());
					}
					break;
				case purge:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "purge", String.class,
					    RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID, new RequestContext());
					}
					
					break;
				case purgeSubresource:
					method = ReflectionUtils.findMethod(resourceHandler.getClass(), "purge", String.class, String.class,
					    RequestContext.class);
					
					if (method == null) {
						return false;
					} else {
						method.invoke(resourceHandler, RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID,
						    RestConstants.SWAGGER_IMPOSSIBLE_UNIQUE_ID, new RequestContext());
					}
			}
			return true;
		}
		catch (Exception e) {
			if (e instanceof ResourceDoesNotSupportOperationException
			        || e.getCause() instanceof ResourceDoesNotSupportOperationException) {
				return false;
			} else {
				return true;
			}
		}
	}
	
	private void SortResourceHandlers(List<DelegatingResourceHandler<?>> resourceHandlers) {
		Collections.sort(resourceHandlers, new Comparator<DelegatingResourceHandler<?>>() {
			
			@Override
			public int compare(DelegatingResourceHandler<?> left, DelegatingResourceHandler<?> right) {
				return isSubclass(left).compareTo(isSubclass(right));
			}
			
			private Boolean isSubclass(DelegatingResourceHandler<?> resourceHandler) {
				return resourceHandler.getClass().getAnnotation(SubResource.class) != null;
			}
		});
	}
	
	private void addResourceTag(String tagString) {
		if (!tags.containsKey(tagString)) {
			Tag tag = new Tag();
			tag.setName(tagString);
			tags.put(tagString, tag);
		}
	}
	
	private ResourceRepresentation getGETRepresentation(DelegatingResourceHandler<?> resourceHandler) {
		ResourceRepresentation getRepresentation = null;
		try {
			// first try the full representation
			getRepresentation = new ResourceRepresentation("GET", resourceHandler
			        .getRepresentationDescription(Representation.FULL).getProperties().keySet());
			return getRepresentation;
		}
		catch (Exception e) {
			// don't panic
		}
		try {
			// next try the full representation
			getRepresentation = new ResourceRepresentation("GET", resourceHandler
					.getRepresentationDescription(Representation.DEFAULT).getProperties().keySet());
			return getRepresentation;
		}
		catch (Exception e) {
			// don't panic
		}
		return getRepresentation;
	}
	
	private ResourceRepresentation getPOSTCreateRepresentation(DelegatingResourceHandler<?> resourceHandler) {
		ResourceRepresentation postCreateRepresentation = null;
		try {
			DelegatingResourceDescription description = resourceHandler.getCreatableProperties();
			List<String> properties = getPOSTProperties(description);
			postCreateRepresentation = new ResourceRepresentation("POST create", properties);
		}
		catch (Exception e) {
			// don't panic
		}
		return postCreateRepresentation;
	}
	
	private SimpleObject buildPOSTCreateSimpleObject(DelegatingResourceHandler<?> resourceHandler) {
		SimpleObject simpleObject = new SimpleObject();
		
		for (String property : resourceHandler.getCreatableProperties().getProperties().keySet()) {
			simpleObject.put(property, property);
		}
		
		return simpleObject;
	}
	
	private SimpleObject buildPOSTUpdateSimpleObject(DelegatingResourceHandler<?> resourceHandler) {
		SimpleObject simpleObject = new SimpleObject();
		
		for (String property : resourceHandler.getUpdatableProperties().getProperties().keySet()) {
			simpleObject.put(property, property);
		}
		
		return simpleObject;
	}
	
	private ResourceRepresentation getPOSTUpdateRepresentation(DelegatingResourceHandler<?> resourceHandler) {
		ResourceRepresentation postCreateRepresentation = null;
		try {
			DelegatingResourceDescription description = resourceHandler.getUpdatableProperties();
			List<String> properties = getPOSTProperties(description);
			postCreateRepresentation = new ResourceRepresentation("POST update", properties);
		}
		catch (Exception e) {
			// don't panic
		}
		return postCreateRepresentation;
	}
	
	/**
	 * Build the Path object for doing a fetch all operation at /resource
	 * 
	 * @param resourceHandler
	 * @param resourceName
	 * @param resourceParentName
	 */
	private Path buildFetchAllPath(Path path, DelegatingResourceHandler<?> resourceHandler, String resourceName,
	        String resourceParentName) {
		
		ResourceRepresentation getRepresentation = getGETRepresentation(resourceHandler);
		
		if (getRepresentation != null) {
			Operation getOperation = null;
			if (resourceParentName == null) {
				if (TestOperationImplemented(OperationEnum.get, resourceHandler)) {
					
					getOperation = createOperation("get", resourceName, getRepresentation, OperationEnum.get);
				}
			} else {
				if (TestOperationImplemented(OperationEnum.getSubresource, resourceHandler)) {
					getOperation = createOperation("get", resourceName, getRepresentation, OperationEnum.getSubresource);
				}
			}
			
			if (getOperation != null) {
				Map<String, Operation> operationsMap = path.getOperations();
				
				String tag = resourceParentName == null ? resourceName : resourceParentName;
				addResourceTag(tag);
				
				getOperation.setTags(Arrays.asList(tag));
				operationsMap.put("get", getOperation);
				path.setOperations(operationsMap);
			}
		}
		
		return path;
	}
	
	/**
	 * Build the Path object for doing a GET at /resource/uuid
	 * 
	 * @param resourceHandler
	 * @param resourceName
	 * @param resourceParentName
	 * @return
	 */
	private Path buildGetWithUUIDPath(Path path, DelegatingResourceHandler<?> resourceHandler, String resourceName,
	        String resourceParentName) {
		
		ResourceRepresentation getRepresentation = getGETRepresentation(resourceHandler);
		
		if (getRepresentation != null) {
			Operation getOperation = null;
			
			if (TestOperationImplemented(OperationEnum.getWithUUID, resourceHandler)) {
				if (resourceParentName == null) {
					getOperation = createOperation("get", resourceName, getRepresentation, OperationEnum.getWithUUID);
				} else {
					getOperation = createOperation("get", resourceName, getRepresentation,
					    OperationEnum.getSubresourceWithUUID);
				}
			}
			
			if (getOperation != null) {
				Map<String, Operation> operationsMap = path.getOperations();
				
				String tag = resourceParentName == null ? resourceName : resourceParentName;
				addResourceTag(tag);
				
				getOperation.setTags(Arrays.asList(tag));
				operationsMap.put("get", getOperation);
				path.setOperations(operationsMap);
			}
		}
		
		return path;
	}
	
	/**
	 * Build the Path object for resource creation at /resource
	 * 
	 * @param resourceHandler
	 * @param resourceName
	 * @param resourceParentName
	 * @return
	 */
	private Path buildCreatePath(Path path, DelegatingResourceHandler<?> resourceHandler, String resourceName,
	        String resourceParentName) {
		
		ResourceRepresentation postCreateRepresentation = getPOSTCreateRepresentation(resourceHandler);
		
		if (postCreateRepresentation != null) {
			Operation postCreateOperation = null;
			
			if (resourceParentName == null) {
				if (TestOperationImplemented(OperationEnum.postCreate, resourceHandler)) {
					postCreateOperation = createOperation("post", resourceName, postCreateRepresentation,
					    OperationEnum.postCreate);
				}
			} else {
				if (TestOperationImplemented(OperationEnum.postSubresource, resourceHandler)) {
					postCreateOperation = createOperation("post", resourceName, postCreateRepresentation,
					    OperationEnum.postSubresource);
				}
			}
			
			if (postCreateOperation != null) {
				Map<String, Operation> operationsMap = path.getOperations();
				
				String tag = resourceParentName == null ? resourceName : resourceParentName;
				addResourceTag(tag);
				
				postCreateOperation.setTags(Arrays.asList(tag));
				operationsMap.put("post", postCreateOperation);
				path.setOperations(operationsMap);
			}
		}
		
		return path;
	}
	
	/**
	 * Build the Path object for resource updating at /resource/uuid
	 * 
	 * @param resourceHandler
	 * @param resourceName
	 * @param resourceParentName
	 * @return
	 */
	private Path buildUpdatePath(Path path, DelegatingResourceHandler<?> resourceHandler, String resourceName,
	        String resourceParentName) {
		
		ResourceRepresentation postUpdateRepresentation = getPOSTUpdateRepresentation(resourceHandler);
		
		if (postUpdateRepresentation != null) {
			Operation postUpdateOperation = null;
			
			if (TestOperationImplemented(OperationEnum.postUpdate, resourceHandler)) {
				if (resourceParentName == null) {
					postUpdateOperation = createOperation("post", resourceName, postUpdateRepresentation,
					    OperationEnum.postUpdate);
				} else {
					postUpdateOperation = createOperation("post", resourceName, postUpdateRepresentation,
					    OperationEnum.postUpdateSubresouce);
				}
			}
			
			if (postUpdateOperation != null) {
				Map<String, Operation> operationsMap = path.getOperations();
				
				String tag = resourceParentName == null ? resourceName : resourceParentName;
				addResourceTag(tag);
				
				postUpdateOperation.setTags(Arrays.asList(tag));
				operationsMap.put("post", postUpdateOperation);
				path.setOperations(operationsMap);
			}
		}
		
		return path;
	}
	
	/**
	 * Build the Path object for deleting a resource at /resource/uuid
	 * 
	 * @param resourceHandler
	 * @param resourceName
	 * @param resourceParentName
	 * @return
	 */
	private Path buildDeletePath(Path path, DelegatingResourceHandler<?> resourceHandler, String resourceName,
	        String resourceParentName) {
		
		Operation deleteOperation = null;
		
		if (resourceParentName == null) {
			if (TestOperationImplemented(OperationEnum.delete, resourceHandler)) {
				deleteOperation = createOperation("delete", resourceName, new ResourceRepresentation("delete",
				        new ArrayList()), OperationEnum.delete);
			}
		} else {
			if (TestOperationImplemented(OperationEnum.deleteSubresource, resourceHandler)) {
				deleteOperation = createOperation("delete", resourceName, new ResourceRepresentation("delete",
				        new ArrayList()), OperationEnum.deleteSubresource);
			}
		}
		
		if (deleteOperation != null) {
			Map<String, Operation> operationsMap = path.getOperations();
			
			String tag = resourceParentName == null ? resourceName : resourceParentName;
			addResourceTag(tag);
			
			deleteOperation.setTags(Arrays.asList(tag));
			operationsMap.put("delete", deleteOperation);
			path.setOperations(operationsMap);
		}
		
		return path;
	}
	
	/**
	 * Build the Path object for purging a resource at /resource/uuid
	 * 
	 * @param resourceHandler
	 * @param resourceName
	 * @return
	 */
	private Path buildPurgePath(Path path, DelegatingResourceHandler<?> resourceHandler, String resourceName,
	        String resourceParentName) {
		
		Operation purgeOperation = null;
		
		if (resourceParentName == null) {
			if (TestOperationImplemented(OperationEnum.purge, resourceHandler)) {
				purgeOperation = createOperation("delete", resourceName,
				    new ResourceRepresentation("purge", new ArrayList()), OperationEnum.purge);
			}
		} else {
			if (TestOperationImplemented(OperationEnum.purgeSubresource, resourceHandler)) {
				purgeOperation = createOperation("delete", resourceName,
				    new ResourceRepresentation("purge", new ArrayList()), OperationEnum.purgeSubresource);
			}
		}
		
		if (purgeOperation != null) {
			Map<String, Operation> operationsMap = path.getOperations();
			
			String tag = resourceParentName == null ? resourceName : resourceParentName;
			addResourceTag(tag);
			
			purgeOperation.setTags(Arrays.asList(tag));
			operationsMap.put("delete", purgeOperation);
			path.setOperations(operationsMap);
		}
		
		return path;
	}
	
	private void addIndividualPath(Map<String, Path> pathMap, Path pathCheck, String resourceParentName,
	        String resourceName, Path path, String pathSuffix) {
		if (pathCheck != null) {
			if (resourceParentName == null) {
				pathMap.put("/" + resourceName + pathSuffix, path);
			} else {
				pathMap.put("/" + resourceParentName + "/{uuid}/" + resourceName + pathSuffix, path);
			}
		}
	}
	
	private String buildSearchParameterDependencyString(Set<String> dependencies) {
		StringBuffer sb = new StringBuffer();
		
		sb.append("Must be used with ");
		sb.append(StringUtils.join(dependencies, ", "));
		
		String ret = sb.toString();
		int ind = ret.lastIndexOf(", ");

		if(ind > -1) {
			ret = new StringBuilder(ret).replace(ind, ind + 2, " or ").toString();
		}
		
		return ret;
	}
	
	private void addSearchOperations(String resourceName, String resourceParentName, Path getAllPath,
	        Map<String, Path> pathMap) {
		boolean wasNew = false;
		
		if (resourceName != null && hasSearchHandler(resourceName)) {
			// if the path has no operations, add a note that search parameters are mandatory
			Operation get;
			if (getAllPath.getOperations().isEmpty() || getAllPath.getOperations().get("get") == null) {
				// create search-only operation
				get = new Operation();
				
				get.setSummary("Search for " + resourceName);
				get.setDescription("At least one search parameter must be specified");
				
				// produces
				List<String> produces = new ArrayList<String>();
				produces.add("application/json");
				produces.add("application/xml");
				get.setProduces(produces);
				
				// schema
				Response statusOKResponse = new Response();
				statusOKResponse.setDescription(resourceName + " response");
				Schema schema = new Schema();
				
				// response
				statusOKResponse.setSchema(schema);
				List<String> resourceTags = new ArrayList<String>();
				resourceTags.add(resourceName);
				get.setTags(resourceTags);
				Map<String, Response> responses = new HashMap<String, Response>();
				responses.put("200", statusOKResponse);
				get.setResponses(responses);
				
				wasNew = true;
			} else {
				get = getAllPath.getOperations().get("get");
				get.setSummary("Fetch all non-retired " + resourceName + " resources or perform search");
				get.setDescription("All search parameters are optional");
			}
			
			List<Parameter> parameterList = get.getParameters() == null ? new ArrayList<Parameter>() : get.getParameters();
			
			// FIXME: this isn't perfect, it doesn't cover the case where multiple parameters are required together
			// FIXME: See https://github.com/OAI/OpenAPI-Specification/issues/256
			for (SearchHandler searchHandler : Context.getService(RestService.class).getAllSearchHandlers()) {
				
				String supportedResourceWithVersion = searchHandler.getSearchConfig().getSupportedResource();
				String supportedResource = supportedResourceWithVersion
				        .substring(supportedResourceWithVersion.indexOf('/') + 1);
				
				if (resourceName.equals(supportedResource)) {
					for (SearchQuery searchQuery : searchHandler.getSearchConfig().getSearchQueries()) {
						// parameters with no dependencies
						for (String requiredParameter : searchQuery.getRequiredParameters()) {
							Parameter p = new Parameter();
							p.setName(requiredParameter);
							p.setIn("query");
							parameterList.add(p);
						}
						// parameters with dependencies
						for (String requiredParameter : searchQuery.getOptionalParameters()) {
							Parameter p = new Parameter();
							p.setName(requiredParameter);
							p.setDescription(buildSearchParameterDependencyString(searchQuery.getRequiredParameters()));
							p.setIn("query");
							parameterList.add(p);
						}
					}
				}
			}
			
			get.setParameters(parameterList);
			
			if (wasNew) {
				getAllPath.getOperations().put("get", get);
				addIndividualPath(pathMap, getAllPath, resourceParentName, resourceName, getAllPath, "");
			}
		}
	}
	
	private void BetterAddPaths() {
		Map<String, Path> pathMap = new HashMap<String, Path>();
		Definitions definitions = new Definitions();
		
		// get all registered resource handlers
		List<DelegatingResourceHandler<?>> resourceHandlers = Context.getService(RestService.class).getResourceHandlers();
		SortResourceHandlers(resourceHandlers);
		
		// generate swagger JSON for each handler
		for (DelegatingResourceHandler<?> resourceHandler : resourceHandlers) {
			
			// get name and parent if it's a subresource
			Resource annotation = resourceHandler.getClass().getAnnotation(Resource.class);
			
			String resourceParentName = null;
			String resourceName = null;
			
			if (annotation != null) {
				// top level resource
				resourceName = annotation.name().substring(annotation.name().indexOf('/') + 1, annotation.name().length());
			} else {
				// subresource
				SubResource subResourceAnnotation = resourceHandler.getClass().getAnnotation(SubResource.class);
				
				if (subResourceAnnotation != null) {
					Resource parentResourceAnnotation = subResourceAnnotation.parent().getAnnotation(Resource.class);
					
					resourceName = subResourceAnnotation.path();
					resourceParentName = parentResourceAnnotation.name().substring(
					    parentResourceAnnotation.name().indexOf('/') + 1, parentResourceAnnotation.name().length());
				}
			}
			
			// TODO: Figure out this subtype handler thing:
			
			//			Class<?> resourceClass = ((DelegatingSubclassHandler<?, ?>) resourceHandler).getSuperclass();
			//			instance = Context.getService(RestService.class).getResourceBySupportedClass(resourceClass);
			//
			//			resourceDoc.setSubtypeHandlerForResourceName(resourceClass.getSimpleName());
			//			resourceDoc.addSubtypeHandler(new ResourceDoc(resourceDoc.getName()));
			if (resourceHandler instanceof DelegatingSubclassHandler)
				continue;
			
			// Set up paths
			Path rootPath = new Path();
			rootPath.setOperations(new HashMap<String, Operation>());
			
			Path uuidPath = new Path();
			uuidPath.setOperations(new HashMap<String, Operation>());
			
			Path purgePath = new Path();
			purgePath.setOperations(new HashMap<String, Operation>());
			
			/////////////////////////
			// GET all             //
			/////////////////////////
			Path rootPathGetAll = buildFetchAllPath(rootPath, resourceHandler, resourceName, resourceParentName);
			addIndividualPath(pathMap, rootPathGetAll, resourceParentName, resourceName, rootPathGetAll, "");

			/////////////////////////
			// GET search          //
			/////////////////////////
			addSearchOperations(resourceName, resourceParentName, rootPathGetAll, pathMap);

			/////////////////////////
			// POST create         //
			/////////////////////////
			Path rootPathPostCreate = buildCreatePath(rootPathGetAll, resourceHandler, resourceName, resourceParentName);
			addIndividualPath(pathMap, rootPathPostCreate, resourceParentName, resourceName, rootPathPostCreate, "");


			//			if (resourceName != null) {
			//				if (hasSearchHandler(resourceName)) {
			//					List<Operation> searchHandlerOperations = createSearchHandlersOperations(resourceName);
			//
			//					for (Operation operation : searchHandlerOperations) {
			//						Map<String, Operation> searchHandlerMap = new HashMap<String, Operation>();
			//						searchHandlerMap.put("get", operation);
			//						Path searchHandlerPath = new Path();
			//						searchHandlerPath.setOperations(searchHandlerMap);
			//						StringBuffer buffer = new StringBuffer();
			//						for (int i = 0; i < operation.getParameters().size(); i++) {
			//							buffer.append(operation.getParameters().get(i).getName());
			//							if (i != operation.getParameters().size() - 1) {
			//								buffer.append(",");
			//							}
			//						}
			//						pathMap.put("/" + resourceName + " (Search by parameters: " + buffer.toString() + ")",
			//						    searchHandlerPath);
			//					}
			//				}
			//			}
			
			/////////////////////////
			// GET with UUID       //
			/////////////////////////
			Path uuidPathGetAll = buildGetWithUUIDPath(uuidPath, resourceHandler, resourceName, resourceParentName);
			addIndividualPath(pathMap, uuidPathGetAll, resourceParentName, resourceName, uuidPathGetAll, "/{uuid}");
			
			/////////////////////////
			// POST update         //
			/////////////////////////
			Path uuidPathPostUpdate = buildUpdatePath(uuidPathGetAll, resourceHandler, resourceName, resourceParentName);
			addIndividualPath(pathMap, uuidPathGetAll, resourceName, resourceParentName, uuidPathPostUpdate, "/{uuid}");
			
			/////////////////////////
			// DELETE              //
			/////////////////////////
			Path uuidPathDelete = buildDeletePath(uuidPathPostUpdate, resourceHandler, resourceName, resourceParentName);
			addIndividualPath(pathMap, uuidPathDelete, resourceName, resourceParentName, uuidPathDelete, "/{uuid}");
			
			/////////////////////////
			// DELETE (purge)      //
			/////////////////////////
			Path uuidPathPurge = buildPurgePath(purgePath, resourceHandler, resourceName, resourceParentName);
			addIndividualPath(pathMap, uuidPathPurge, resourceName, resourceParentName, uuidPathPurge, "/{uuid}");
		}
		
		Paths paths = new Paths();
		paths.setPaths(pathMap);
		swaggerSpecification.setPaths(paths);
		swaggerSpecification.setTags(new ArrayList(tags.values()));
	}
	
	private void createObjectDefinitions() {
		Definitions definitions = new Definitions();
		Map<String, Definition> definitionsMap = new HashMap<String, Definition>();
		
		for (ResourceDoc doc : resourceDocList) {
			String resourceLongName = doc.getResourceName();
			if (resourceLongName != null) {
				Definition definition = new Definition();
				definition.setType("object");
				Properties properties = new Properties();
				Map<String, DefinitionProperty> propertiesMap = new HashMap<String, DefinitionProperty>();
				String resourceName = (resourceLongName.split("/"))[1];
				//String resourceName = doc.getName();
				String resourceDefinitionName = resourceName;
				for (ResourceRepresentation representation : doc.getRepresentations()) {
					String tempRepresentationName = representation.getName();
					String tempOperation = (tempRepresentationName.split(" "))[0];
					String operationType = (tempRepresentationName.split(" "))[1];
					for (String representationProperty : representation.getProperties()) {
						DefinitionProperty property = new DefinitionProperty();
						//all properties are of type string
						if (!representationProperty.equals("Not supported")) {
							property.setType("string");
							String propertyNameWithoutStar = "";
							if (representationProperty.startsWith("*")) {
								propertyNameWithoutStar = representationProperty.replace("*", "");
							} else {
								propertyNameWithoutStar = representationProperty;
							}
							
							propertiesMap.put(propertyNameWithoutStar, property);
						}
					}
					//Definitions for POST CREATE and POST UPDATE
					if (!tempOperation.equals("GET")) {
						if (operationType.equals("create")) {
							resourceDefinitionName = resourceName + "CreateInput";
						} else {
							resourceDefinitionName = resourceName + "UpdateInput";
						}
						
					}
					properties.setProperties(propertiesMap);
					definition.setProperties(properties);
					definitionsMap.put(resourceDefinitionName, definition);
					
				}
				
			}
		}
		
		definitions.setDefinitions(definitionsMap);
		swaggerSpecification.setDefinitions(definitions);
	}
	
	/**
	 * @return the swaggerSpecification
	 */
	public SwaggerSpecification getSwaggerSpecification() {
		return swaggerSpecification;
	}
	
	private static List<String> getPOSTProperties(DelegatingResourceDescription description) {
		List<String> properties = new ArrayList<String>();
		for (Entry<String, Property> property : description.getProperties().entrySet()) {
			if (property.getValue().isRequired()) {
				properties.add("*" + property.getKey() + "*");
			} else {
				properties.add(property.getKey());
			}
		}
		return properties;
	}
	
	private List<Parameter> getParametersList(Collection<String> properties, String resourceName, OperationEnum operationEnum) {
		List<Parameter> parameters = new ArrayList<Parameter>();
		String resourceURL = getResourceUrl(getBaseUrl(), resourceName);
		if (operationEnum == OperationEnum.get) {
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
			}
		} else if (operationEnum == OperationEnum.getWithUUID || operationEnum == OperationEnum.getSubresource) {
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
			}
			Parameter parameter = new Parameter();
			parameter.setName("uuid");
			parameter.setIn("path");
			parameter.setDescription("uuid to filter by");
			parameter.setRequired(true);
			parameters.add(parameter);
		} else if (operationEnum == OperationEnum.postCreate) {
			StringBuffer buffer = new StringBuffer();
			buffer.append("Parameters: ");
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
				
				if (property.startsWith("*")) {
					
					String propertyStringWithoutStar = property.replace("*", "");
					buffer.append(propertyStringWithoutStar + "(required)");
				} else {
					buffer.append(property + "(optional)");
				}
				buffer.append(" ");
			}
			
			Parameter parameter = new Parameter();
			parameter.setName("Object to create");
			parameter.setIn("body");
			parameter.setDescription(buffer.toString());
			parameter.setRequired(true);
			parameters.add(parameter);
			
		} else if (operationEnum == OperationEnum.postSubresource) {
			StringBuffer buffer = new StringBuffer();
			buffer.append("Parameters: ");
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
				
				if (property.startsWith("*")) {
					
					String propertyStringWithoutStar = property.replace("*", "");
					buffer.append(propertyStringWithoutStar + "(required)");
				} else {
					buffer.append(property + "(optional)");
				}
				buffer.append(" ");
			}
			
			Parameter parameter = new Parameter();
			parameter.setName("Object to create");
			parameter.setIn("body");
			parameter.setDescription(buffer.toString());
			parameter.setRequired(true);
			parameters.add(parameter);
			
			Parameter parameter2 = new Parameter();
			parameter2.setName("uuid");
			parameter2.setIn("path");
			parameter2.setDescription("uuid to filter by");
			parameter2.setRequired(true);
			parameters.add(parameter2);
			
		} else if (operationEnum == OperationEnum.postUpdate) {
			StringBuffer buffer = new StringBuffer();
			buffer.append("Parameters: ");
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
				
				if (property.startsWith("*")) {
					
					String propertyStringWithoutStar = property.replace("*", "");
					buffer.append(propertyStringWithoutStar + "(required)");
				} else {
					buffer.append(property + "(optional)");
				}
				buffer.append(" ");
				
			}
			
			Parameter parameter = new Parameter();
			parameter.setName("uuid");
			parameter.setIn("path");
			parameter.setDescription("uuid of the resource to update");
			parameter.setRequired(true);
			parameters.add(parameter);
			
			Parameter parameter2 = new Parameter();
			
			parameter2.setName("Object to update");
			parameter2.setIn("body");
			parameter2.setRequired(true);
			parameter2.setDescription(buffer.toString());
			
			parameters.add(parameter2);
			
		} else if (operationEnum == OperationEnum.getSubresourceWithUUID) {
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
			}
			Parameter parameter = new Parameter();
			parameter.setName("uuid");
			parameter.setIn("path");
			parameter.setDescription("uuid to filter by");
			parameter.setRequired(true);
			parameters.add(parameter);
			
			Parameter parameter2 = new Parameter();
			parameter2.setName("subresource-uuid");
			parameter2.setIn("path");
			parameter2.setDescription("subresource uuid to filter by");
			parameter2.setRequired(true);
			parameters.add(parameter2);
		} else if (operationEnum == OperationEnum.postUpdateSubresouce) {
			StringBuffer buffer = new StringBuffer();
			buffer.append("Parameters: ");
			for (String property : properties) {
				if (property.equals("Not supported")) {
					return null;
				}
				
				if (property.startsWith("*")) {
					
					String propertyStringWithoutStar = property.replace("*", "");
					buffer.append(propertyStringWithoutStar + "(required)");
				} else {
					buffer.append(property + "(optional)");
				}
				buffer.append(" ");
				
			}
			
			Parameter parameter = new Parameter();
			parameter.setName("uuid");
			parameter.setIn("path");
			parameter.setDescription("uuid of the resource to update");
			parameter.setRequired(true);
			parameters.add(parameter);
			
			Parameter parameter2 = new Parameter();
			
			parameter2.setName("Object to update");
			parameter2.setIn("body");
			parameter2.setRequired(true);
			parameter2.setDescription(buffer.toString());
			
			parameters.add(parameter2);
			
			Parameter parameter3 = new Parameter();
			parameter3.setName("subresource-uuid");
			parameter3.setIn("path");
			parameter3.setRequired(true);
			parameter3.setDescription("subresource uuid to filter by");
			
			parameters.add(parameter3);
		} else if (operationEnum == OperationEnum.delete || operationEnum == OperationEnum.deleteSubresource) {
			Parameter parameter = new Parameter();
			parameter.setName("uuid");
			parameter.setIn("path");
			parameter.setDescription("uuid to delete");
			parameter.setRequired(true);
			parameters.add(parameter);
		} else if (operationEnum == OperationEnum.purge || operationEnum == OperationEnum.purgeSubresource) {
			Parameter parameter = new Parameter();
			parameter.setName("uuid");
			parameter.setIn("path");
			parameter.setDescription("uuid to purge");
			parameter.setRequired(true);
			parameters.add(parameter);
			
			Parameter parameter2 = new Parameter();
			parameter2.setName("purge");
			parameter2.setIn("query");
			parameter2.setDescription("purge flag");
			parameter2.setRequired(true);
			parameters.add(parameter2);
		}
		
		return parameters;
	}
	
	private List<Parameter> getParametersListForSearchHandlers(String resourceName, String searchHandlerId, int queryIndex) {
		List<Parameter> parameters = new ArrayList<Parameter>();
		String resourceURL = getResourceUrl(getBaseUrl(), resourceName);
		for (SearchHandlerDoc searchDoc : searchHandlerDocs) {
			if (searchDoc.getSearchHandlerId().equals(searchHandlerId) && searchDoc.getResourceURL().equals(resourceURL)) {
				SearchQueryDoc queryDoc = searchDoc.getSearchQueriesDoc().get(queryIndex);
				for (String requiredParameter : queryDoc.getRequiredParameters()) {
					Parameter parameter = new Parameter();
					parameter.setName(requiredParameter);
					parameter.setIn("query");
					parameter.setDescription("");
					parameter.setRequired(true);
					parameters.add(parameter);
				}
				for (String optionalParameter : queryDoc.getOptionalParameters()) {
					Parameter parameter = new Parameter();
					parameter.setName(optionalParameter);
					parameter.setIn("query");
					parameter.setDescription("");
					parameter.setRequired(false);
					parameters.add(parameter);
				}
				
				break;
			}
		}
		return parameters;
		
	}
	
	private String createJSON() {
		String json = "";
		try {
			ObjectMapper mapper = new ObjectMapper();
			mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
			mapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, true);
			mapper.setSerializationInclusion(Include.NON_NULL);
			mapper.getSerializerProvider().setNullKeySerializer(new NullSerializer());
			
			json = mapper.writeValueAsString(swaggerSpecification);
		}
		catch (Exception exp) {
			exp.printStackTrace();
		}
		
		return json;
	}
	
	private void addResourceTags() {
		
		List<Tag> tags = new ArrayList<Tag>();
		for (ResourceDoc doc : resourceDocList) {
			String resourceLongName = doc.getResourceName();
			if (resourceLongName != null) {
				String resourceName = (resourceLongName.split("/"))[1];
				Tag tag = new Tag();
				tag.setName(resourceName);
				/* For now, we do not add any description */
				tag.setDescription("");
				tags.add(tag);
			} else {
				for (ResourceDoc subType : doc.getSubtypeHandlers()) {
					Tag subTypeTag = new Tag();
					subTypeTag.setName(subType.getName());
					
					subTypeTag.setDescription("subtype of  " + doc.getSubtypeHandlerForResourceName());
					tags.add(subTypeTag);
				}
				
			}
		}
		
		swaggerSpecification.setTags(tags);
	}
	
	private Operation createOperation(String operationName, String resourceName, ResourceRepresentation representation,
	        OperationEnum operationEnum) {
		
		Operation operation = new Operation();
		operation.setName(operationName);
		operation.setDescription(null);
		
		List<String> produces = new ArrayList<String>();
		produces.add("application/json");
		produces.add("application/xml");
		operation.setProduces(produces);
		List<Parameter> parameters = new ArrayList<Parameter>();
		
		parameters = getParametersList(representation.getProperties(), resourceName, operationEnum);
		
		if (parameters == null)
			return null;
		
		operation.setParameters(parameters);
		
		Response statusOKResponse = new Response();
		statusOKResponse.setDescription(resourceName + " response");
		Schema schema = new Schema();
		if (operationEnum == OperationEnum.get) {
			schema.setRef("#/definitions/" + resourceName);
			operation.setSummary("Fetch all non-retired");
		} else if (operationEnum == OperationEnum.getWithUUID) {
			schema.setRef("#/definitions/" + resourceName);
			operation.setSummary("Fetch by uuid");
		} else if (operationEnum == OperationEnum.postCreate) {
			schema.setRef("#/definitions/" + resourceName + "createInput");
			operation.setSummary("Create with properties in request");
		} else if (operationEnum == OperationEnum.postUpdate) {
			schema.setRef("#/definitions/" + resourceName + "updateInput");
			operation.setSummary("Edit with given uuid, only modifying properties in request");
		} else if (operationEnum == OperationEnum.getSubresource) {
			operation.setSummary("Fetch all non-retired " + resourceName + " subresources");
		} else if (operationEnum == OperationEnum.postSubresource) {
			operation.setSummary("Create " + resourceName + " subresource with properties in request");
		} else if (operationEnum == OperationEnum.postUpdateSubresouce) {
			operation.setSummary("Edit " + resourceName
			        + " subresource with given uuid, only modifying properties in request");
		} else if (operationEnum == OperationEnum.getSubresourceWithUUID) {
			operation.setSummary("Fetch " + resourceName + " subresources by uuid");
		} else if (operationEnum == OperationEnum.delete) {
			operation.setSummary("Delete resource by uuid");
		} else if (operationEnum == OperationEnum.deleteSubresource) {
			operation.setSummary("Delete " + resourceName + " subresource by uuid");
		} else if (operationEnum == OperationEnum.purge) {
			operation.setSummary("Purge resource by uuid");
		} else if (operationEnum == OperationEnum.purgeSubresource) {
			operation.setSummary("Purge " + resourceName + " subresource by uuid");
		}
		
		statusOKResponse.setSchema(schema);
		List<String> resourceTags = new ArrayList<String>();
		resourceTags.add(resourceName);
		operation.setTags(resourceTags);
		Map<String, Response> responses = new HashMap<String, Response>();
		responses.put("200", statusOKResponse);
		operation.setResponses(responses);
		
		return operation;
	}
	
	private Operation createSearchHandlerOperation(String operationName, String resourceName, String searchHandlerId,
	        OperationEnum operationEnum, int queryIndex) {
		
		Operation operation = new Operation();
		operation.setName(operationName);
		operation.setDescription(null);
		List<String> produces = new ArrayList<String>();
		produces.add("application/json");
		operation.setProduces(produces);
		operation.setIsSearchHandler("true");
		List<Parameter> parameters = new ArrayList<Parameter>();
		
		parameters = getParametersListForSearchHandlers(resourceName, searchHandlerId, queryIndex);
		
		operation.setParameters(parameters);
		
		Response statusOKResponse = new Response();
		statusOKResponse.setDescription(resourceName + " response");
		Schema schema = new Schema();
		
		schema.setRef("#/definitions/" + resourceName);
		
		statusOKResponse.setSchema(schema);
		
		List<String> resourceTags = new ArrayList<String>();
		resourceTags.add(resourceName);
		operation.setTags(resourceTags);
		
		Map<String, Response> responses = new HashMap<String, Response>();
		responses.put("200", statusOKResponse);
		
		operation.setResponses(responses);
		
		String resourceURL = getResourceUrl(getBaseUrl(), resourceName);
		for (SearchHandlerDoc searchDoc : searchHandlerDocs) {
			if (searchDoc.getSearchHandlerId().equals(searchHandlerId) && searchDoc.getResourceURL().equals(resourceURL)) {
				SearchQueryDoc queryDoc = searchDoc.getSearchQueriesDoc().get(queryIndex);
				operation.setSummary(queryDoc.getDescription());
			}
		}
		
		return operation;
	}
	
	private static List<SearchHandlerDoc> fillSearchHandlers(List<SearchHandler> searchHandlers, String url) {
		
		List<SearchHandlerDoc> searchHandlerDocList = new ArrayList<SearchHandlerDoc>();
		String baseUrl = url.replace("/rest", "");
		
		for (int i = 0; i < searchHandlers.size(); i++) {
			if (searchHandlers.get(i) != null) {
				SearchHandler searchHandler = searchHandlers.get(i);
				SearchHandlerDoc searchHandlerDoc = new SearchHandlerDoc(searchHandler, baseUrl);
				searchHandlerDocList.add(searchHandlerDoc);
			}
		}
		
		return searchHandlerDocList;
	}
	
	private String getResourceUrl(String baseUrl, String resourceName) {
		
		String resourceUrl = baseUrl;
		
		//Set the root url.
		return resourceUrl + "/v1/" + resourceName;
		
	}
	
	private boolean hasSearchHandler(String resourceName) {
		for (SearchHandlerDoc doc : searchHandlerDocs) {
			if (doc.getResourceURL().contains(resourceName)) {
				return true;
			}
		}
		
		return false;
	}
	
	private List<Operation> createSearchHandlersOperations(String resourceName) {
		List<Operation> searchHandlersOperations = new ArrayList<Operation>();
		
		for (SearchHandlerDoc doc : searchHandlerDocs) {
			String currentResourceName = doc.getResourceURL().replace(getBaseUrl() + "/v1/", "");
			if (currentResourceName.equals(resourceName)) {
				for (SearchQueryDoc queryDoc : doc.getSearchQueriesDoc()) {
					int queryIndex = doc.getSearchQueriesDoc().indexOf(queryDoc);
					Operation searchHandlerOperation = createSearchHandlerOperation("get", resourceName,
					    doc.getSearchHandlerId(), OperationEnum.getWithSearchHandler, queryIndex);
					searchHandlerOperation.setDescription(queryDoc.getDescription());
					searchHandlersOperations.add(searchHandlerOperation);
				}
			}
		}
		return searchHandlersOperations;
	}
	
	/**
	 * @return the baseUrl
	 */
	public String getBaseUrl() {
		return baseUrl;
	}
	
	/**
	 * @param baseUrl the baseUrl to set
	 */
	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}
	
	private ResourceDoc findResource(String resourceName) {
		for (int i = 0; i < resourceDocList.size(); i++) {
			if (resourceDocList.get(i).getResourceName().equals(resourceName)) {
				return resourceDocList.get(i);
			}
		}
		return null;
	}
	
}
