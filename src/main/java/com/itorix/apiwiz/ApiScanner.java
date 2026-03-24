package com.itorix.apiwiz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.itorix.apiwiz.model.ApiEndpoint;
import io.swagger.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

public class ApiScanner {

    private static Map<String, Schema<?>> componentSchemas = new HashMap<>();
    private static Map<String, Integer> schemaNameCounter = new HashMap<>();
    private static Map<Class<?>, String> classToSchemaName = new HashMap<>();
    private static Map<String, Integer> nameCount = new HashMap<>();
    public static void main(String[] args) throws MalformedURLException {

        String projectBasedir = args[0];
        String fileLocation = args[1];
        File projectRoot = new File(projectBasedir);

        String moduleName = projectRoot.getName();

        List<URL> urls = new ArrayList<>();

        findTargetClasses(projectRoot, urls);
        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]),
                Thread.currentThread().getContextClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);

        Set<Class<?>> controllers = new HashSet<>();
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .addUrls(urls)
                .addClassLoaders(classLoader)
                .setScanners(Scanners.TypesAnnotated));
        controllers.addAll(reflections.getTypesAnnotatedWith(RestController.class));
        controllers.addAll(reflections.getTypesAnnotatedWith(Controller.class));
        controllers.addAll(reflections.getTypesAnnotatedWith(RequestMapping.class));

        List<ApiEndpoint> apiEndpoints = new ArrayList<>();
        OpenAPI openAPI = new OpenAPI();
        if(openAPI.getInfo() == null){
            openAPI.setInfo(new Info().title(formatModuleName(moduleName)).version("v0"));
        }
        if(openAPI.getPaths() == null ){
            openAPI.setPaths(new Paths());
        }
        Server server = new Server();
        server.setUrl("http://localhost:8000");
        if(openAPI.getServers() == null){
            openAPI.setServers(new ArrayList<>());
        }
        Components components = new Components();
        openAPI.getServers().add(server);
        Map<String, Integer> operationNameCount = new HashMap<>();
        Map<String, String> regexMap = new LinkedHashMap<>();
        for (Class<?> controller : controllers) {
            try {
                boolean isInterface = controller.isInterface();
                List<Method> accessibleMethods = getSafeMethods(controller);
                String name = controller.getSimpleName();
                for (Method method : accessibleMethods) {
                    try {
                        String url = getMappingUrl(controller, method);
                        if(url != null && !url.startsWith("/")){
                            url = url.replaceFirst("://([^:]+)(?:.+)@", "://$1@");
                            url = "/" + url;
                        }
                        if (url != null) {
                            url = parsePath(url,regexMap);
                            String methodType = getMethod(method);
                            if (!checkApiExists(isInterface, apiEndpoints, url, methodType, method.getName())) {
                                PathItem pathItem = new PathItem();
                                if(openAPI.getPaths().get(url) != null) {
                                    pathItem = openAPI.getPaths().get(url);
                                }
                                Operation operation = new Operation();
                                if(operation.getParameters() == null) {
                                    operation.setParameters(new ArrayList<>());
                                }
                                String baseName = method.getName();
                                int count = operationNameCount.getOrDefault(baseName, 0);
                                String operationId = (count == 0) ? baseName : baseName + "_" + count;
                                operation.setOperationId(operationId);
                                operationNameCount.put(baseName, count + 1);
                                boolean bodyExists = checkBodyExists(method.getParameters());
                                List<Parameter> bodyParams = new ArrayList<>();
                                for (Parameter parameter : method.getParameters()) {
                                    try {
                                        String paramType = getParameterType(parameter,bodyExists);
                                        if (paramType.equals("Body")) {
                                            try {
                                                operation.setRequestBody(getRequestBody(parameter.getParameterizedType(), components));
                                            } catch (Exception e) {
                                            }
                                        }else if(!bodyExists && (paramType.equals("ModelAttribute") || paramType.equals("RequestPart"))){
                                            bodyParams.add(parameter);
                                        }else if (!paramType.isEmpty()) {
                                            String paramName = getParamName(parameter);
                                            io.swagger.v3.oas.models.parameters.Parameter parameter1 = new io.swagger.v3.oas.models.parameters.Parameter();
                                            parameter1.setName(paramName);
                                            parameter1.setIn(paramType.toLowerCase());
                                            Schema<?> schema = new Schema<>();
                                            Schema<?> jsonObject = new Schema();
                                            try {
                                                Type type = parameter.getParameterizedType();
                                                if(type.getTypeName().equalsIgnoreCase("HttpHeaders") || type.getTypeName().equalsIgnoreCase("org.springframework.http.HttpHeaders")){
                                                    jsonObject.setType("object");
                                                    schema = jsonObject;
                                                }else {
                                                    // Parameters should be INLINE (not component refs)
                                                    Class<?> bodyClass = extractClassFromTypeInline(type, jsonObject);
                                                    if (bodyClass != null) {
                                                        processFieldsSchemaInline(bodyClass, jsonObject, new HashSet<>());
                                                    }
                                                    schema = jsonObject;
                                                }
                                            } catch (NoClassDefFoundError | Exception ex) {
                                            }
                                            parameter1.setSchema(schema);
                                            operation.getParameters().add(parameter1);
                                        }
                                    } catch (NoClassDefFoundError | Exception e) {
                                    }
                                }
                                if(!bodyParams.isEmpty()) {
                                    operation.setRequestBody(getRequestBodyFieldsSchema(bodyParams, components));
                                }
                                try {
                                    operation.setResponses(extractResponseBodies(method,components));
                                } catch (NoClassDefFoundError | Exception e) {
                                }
                                if(operation.getTags() == null){
                                    operation.setTags(new ArrayList<>());
                                    operation.getTags().add(name);
                                }
                                switch (methodType){
                                    case "GET":
                                        pathItem.setGet(operation);
                                        break;
                                    case "POST":
                                        pathItem.setPost(operation);
                                        break;
                                    case "PUT":
                                        pathItem.setPut(operation);
                                        break;
                                    case "DELETE":
                                        pathItem.setDelete(operation);
                                        break;
                                    case "PATCH":
                                        pathItem.setPatch(operation);
                                        break;
                                }
                                openAPI.getPaths().put(url,pathItem);
                            }
                        }
                    } catch (NoClassDefFoundError | Exception e) {
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        openAPI.setComponents(components);
        String name;
        if(openAPI.getInfo().getTitle().equalsIgnoreCase("OpenAPI Definition")){
            name = UUID.randomUUID().toString().replaceAll("[^A-Za-z]", "").substring(0, 5) + "-openapi";
        }else{
            name = openAPI.getInfo().getTitle();
        }
        String openAPIFile = fileLocation +  name + ".json";
        if(openAPI.getPaths() != null && !openAPI.getPaths().isEmpty()) {
            String pretty = Json.pretty(openAPI);
            pretty = pretty.replaceAll("\"exampleSetFlag\"\\s*:\\s*true\\s*,?", "");
            pretty = pretty.replaceAll("\"exampleSetFlag\"\\s*:\\s*false\\s*,?", "");
            pretty = pretty.replaceAll("\"valueSetFlag\"\\s*:\\s*true\\s*,?", "");
            pretty = pretty.replaceAll("\"valueSetFlag\"\\s*:\\s*false\\s*,?", "");
            pretty = pretty.replaceAll("\"style\"\\s*:\\s*\"SIMPLE\"", "\"style\": \"simple\"");
            pretty = pretty.replaceAll("\"style\"\\s*:\\s*\"FORM\"", "\"style\": \"form\"");
            pretty = pretty.replaceAll("\"type\"\\s*:\\s*\"OAUTH2\"", "\"type\": \"oauth2\"");
            pretty = pretty.replaceAll("\"type\"\\s*:\\s*\"HTTP\"", "\"type\": \"http\"");
            pretty = pretty.replaceAll("\"type\"\\s*:\\s*\"OPENIDCONNECT\"", "\"type\": \"openIdConnect\"");
            pretty = pretty.replaceAll("\"type\"\\s*:\\s*\"APIKEY\"", "\"type\": \"apiKey\"");
            pretty = pretty.replaceAll(",\\s*}", " }");
            try (FileWriter writer = new FileWriter(openAPIFile)) {
                writer.write(pretty);
            } catch (IOException e) {
            }
        }
    }

    private static String formatModuleName(String moduleName) {
        if (moduleName == null || moduleName.isEmpty()) {
            return "OpenAPI Definition";
        }
        String[] parts = moduleName.split("[-_]");
        StringBuilder formatted = new StringBuilder();

        for (String part : parts) {
            if (!part.isEmpty()) {
                formatted.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase())
                        .append(" ");
            }
        }

        String name = formatted.toString().trim();
        int count = nameCount.getOrDefault(name, 0);
        return (count == 0) ? name : name + "_" + count;
    }

    // REQUEST BODY - Uses component references
    private static io.swagger.v3.oas.models.parameters.RequestBody getRequestBody(Type type, Components components) {
        io.swagger.v3.oas.models.parameters.RequestBody requestBody = new io.swagger.v3.oas.models.parameters.RequestBody();
        io.swagger.v3.oas.models.media.Content content = new Content();
        MediaType mediaType = new MediaType();
        content.put("application/json", mediaType);

        Schema<?> schema = new Schema<>();
        try {
            Class<?> bodyClass = extractClassFromType(type, schema, components);
            if (bodyClass != null) {
                // Create reference to component schema
                String schemaName = getOrCreateComponentSchema(bodyClass, components);
                schema.set$ref("#/components/schemas/" + schemaName);
            }
            mediaType.setSchema(schema);
        } catch (NoClassDefFoundError | Exception ex) {
            System.out.println("Warning: Could not process body fields for " + type.getTypeName() + ": " + ex.getMessage());
        }

        requestBody.setContent(content);
        return requestBody;
    }


    private static io.swagger.v3.oas.models.parameters.RequestBody getRequestBodyFieldsSchema(List<Parameter> bodyParams,
            Components components) {
        io.swagger.v3.oas.models.parameters.RequestBody requestBody = new io.swagger.v3.oas.models.parameters.RequestBody();
        Schema<?> jsonObject = new Schema<>();
        jsonObject.setType( "object");
        Content content = new Content();
        MediaType mediaType = new MediaType();
        content.put("application/json",mediaType);
        requestBody.setContent(content);

        Map<String, Schema> properties = new HashMap<>();

        try {
            for (Parameter param : bodyParams) {
                String paramName = getParamName(param);
                if (param.isAnnotationPresent(ModelAttribute.class)) {
                    properties.put(paramName,getBodyFieldsSchema(param.getParameterizedType(),components));
                } else if (param.isAnnotationPresent(RequestPart.class)) {
                    properties.put(paramName,getBodyFieldsSchema(param.getParameterizedType(),components));
                }

            }
            jsonObject.setProperties(properties);
            mediaType.setSchema(jsonObject);

            return requestBody;
        } catch (Exception ex) {
        }
        return requestBody;
    }

    private static boolean checkBodyExists(Parameter[] parameters) {
        for (Parameter parameter : parameters) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return true;
            }
        }
        return false;
    }

    private static String getParamName(Parameter parameter) {
        String paramName = parameter.getName();
        RequestHeader headerAnnotation = parameter.getAnnotation(RequestHeader.class);
        if (headerAnnotation != null && !headerAnnotation.value().isEmpty()) {
            paramName = headerAnnotation.value();
        }
        RequestParam paramAnnotation = parameter.getAnnotation(RequestParam.class);
        if (paramAnnotation != null && !paramAnnotation.value().isEmpty()) {
            paramName = paramAnnotation.value();
        }
        PathVariable pathParam = parameter.getAnnotation(PathVariable.class);
        if(pathParam != null && !pathParam.value().isEmpty()){
            paramName = pathParam.value();
        }
        ModelAttribute modelAttribute = parameter.getAnnotation(ModelAttribute.class);
        if(modelAttribute != null && !modelAttribute.value().isEmpty()){
            paramName = modelAttribute.value();
        }
        RequestPart requestPart = parameter.getAnnotation(RequestPart.class);
        if(requestPart != null && !requestPart.value().isEmpty()){
            paramName = requestPart.value();
        }
        return paramName;
    }

    private static List<Method> getSafeMethods(Class<?> controller) {
        List<Method> accessibleMethods = new ArrayList<>();
        try {
            Method[] declaredMethods = controller.getDeclaredMethods();
            for (Method method : declaredMethods) {
                if ((method.isAnnotationPresent(RequestMapping.class) ||
                                method.isAnnotationPresent(GetMapping.class) ||
                                method.isAnnotationPresent(PostMapping.class) ||
                                method.isAnnotationPresent(PutMapping.class) ||
                                method.isAnnotationPresent(DeleteMapping.class) ||
                                method.isAnnotationPresent(PatchMapping.class))) {
                    try {
                        method.getParameterTypes();
                        method.getReturnType();
                        accessibleMethods.add(method);
                    } catch (NoClassDefFoundError | TypeNotPresentException e) {
                    }
                }
            }
        } catch (NoClassDefFoundError e) {
        }
        return accessibleMethods;
    }

    private static boolean checkApiExists(boolean isInterface, List<ApiEndpoint> apiEndpoints,
            String url, String methodType, String name) {
        if(url.endsWith("/")){
            url = url.substring(0, url.length()-1);
        }
        if(isInterface){
            String finalUrl = url;
            apiEndpoints.removeIf(apiEndpoint ->
                    Objects.equals(apiEndpoint.getMethod(), methodType) &&
                            Objects.equals(apiEndpoint.getMethodName(), name) &&
                            (Objects.equals(apiEndpoint.getUrl(), finalUrl) ||
                                    (apiEndpoint.getUrl() != null && apiEndpoint.getUrl().length() > 1 &&
                                            apiEndpoint.getUrl().substring(0, apiEndpoint.getUrl().length() - 1).equals(finalUrl)))
            );
        }else {
            String finalUrl = url;
            Optional<ApiEndpoint> apiEndpointStream = apiEndpoints.stream().filter(apiEndpoint ->
                    Objects.equals(apiEndpoint.getMethod(), methodType) &&
                            Objects.equals(apiEndpoint.getMethodName(), name) &&
                            apiEndpoint.isInterface() &&
                            (Objects.equals(apiEndpoint.getUrl(), finalUrl) ||
                                    (apiEndpoint.getUrl() != null && apiEndpoint.getUrl().length() > 1 &&
                                            apiEndpoint.getUrl().substring(0, apiEndpoint.getUrl().length() - 1)
                                                    .equals(finalUrl)))
            ).findAny();
            return apiEndpointStream.isPresent();
        }
        return false;
    }

    private static String getMethod(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return HttpMethod.GET.name();
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            return HttpMethod.POST.name();
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            return HttpMethod.PUT.name();
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            return HttpMethod.DELETE.name();
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            return HttpMethod.PATCH.name();
        } else if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping annotation = method.getAnnotation(RequestMapping.class);
            if (annotation.method() != null && annotation.method().length > 0) {
                return annotation.method()[0].name();
            }
        }
        return "";
    }

    private static String getParameterType(Parameter parameter, boolean bodyExists) {
        for (Annotation annotation : parameter.getAnnotations()) {
            if (annotation instanceof RequestHeader)
                return "Header";
            if (annotation instanceof RequestParam)
                return "Query";
            if (annotation instanceof PathVariable)
                return "Path";
            if (annotation instanceof RequestBody)
                return "Body";
            if (annotation instanceof ModelAttribute)
                return bodyExists ? "Query" : "ModelAttribute";
            if (annotation instanceof RequestPart)
                return bodyExists ? "Query" : "RequestPart";
        }
        return "";
    }


    private static Schema<?> getBodyFieldsSchema(Type type, Components components) {
        Schema<?> jsonObject = new Schema<>();
        try {
            Class<?> bodyClass = extractClassFromType(type, jsonObject,components);
            if (bodyClass != null) {
                String schemaName = getOrCreateComponentSchema(bodyClass, components);
                jsonObject.set$ref("#/components/schemas/" + schemaName);
            }
            return jsonObject;
        } catch (NoClassDefFoundError | Exception ex) {
        }
        return jsonObject;
    }

    private static Schema<?> getSchemaObject(Type type, Components components) {
        Schema<?> schema = new Schema<>();
        try {
            Class<?> bodyClass = extractClassFromType(type, schema,components);
            if (bodyClass != null) {
                String schemaName = getOrCreateComponentSchema(bodyClass, components);
                schema.set$ref("#/components/schemas/" + schemaName);
                return schema;
            }

        } catch (NoClassDefFoundError | Exception ex) {
            schema = new ObjectSchema();
        }
        return schema;
    }

    private static Class<?> extractClassFromType(Type type, Schema<?> schema, Components components) {
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isArray()) {
                Class<?> componentType = clazz.getComponentType();
                schema.setType("array");
                if (shouldCreateComponentSchema(componentType)) {
                    String schemaName = getOrCreateComponentSchema(componentType, components);
                    Schema<?> items = new Schema<>();
                    items.set$ref("#/components/schemas/" + schemaName);
                    schema.setItems(items);
                } else {
                    Schema<?> items = new Schema<>();
                    processFieldsSchema(componentType, items, new HashSet<>(), components);
                    schema.setItems(items);
                }
                return null;
            }
            return clazz;
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type rawType = parameterizedType.getRawType();

            if (rawType == Optional.class) {
                Type wrappedType = parameterizedType.getActualTypeArguments()[0];
                return extractClassFromType(wrappedType, schema, components);
            }
            if (rawType == List.class || rawType == Collection.class || rawType == Set.class || rawType == HashSet.class || rawType == LinkedList.class || rawType == ArrayList.class || rawType == TreeSet.class || rawType == LinkedHashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                schema.setType("array");
                Schema<?> itemsSchema = createSchemaForType(elementType, components);
                schema.setItems(itemsSchema);
                return null;
            } else if (rawType == Map.class || rawType == TreeMap.class || rawType == HashMap.class || rawType == LinkedHashMap.class) {
                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                Type valueType = typeArguments.length > 1 ? typeArguments[1] : null;
                schema.setType("object");
                Schema<?> additionalProps = createSchemaForType(valueType, components);
                schema.setAdditionalProperties(additionalProps);
                return null;
            }

            return (Class<?>) rawType;
        }
        return null;
    }

    private static Schema<?> createSchemaForType(Type type, Components components) {
        Schema<?> schema = new Schema<>();
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            if (shouldCreateComponentSchema(clazz)) {
                String schemaName = getOrCreateComponentSchema(clazz, components);
                schema.set$ref("#/components/schemas/" + schemaName);
            } else {
                processFieldsSchema(clazz, schema, new HashSet<>(), components);
            }
        } else if (type instanceof ParameterizedType) {
            processGenericTypeSchema(type, schema, new HashSet<>(), components);
        }
        return schema;
    }

    private static boolean shouldCreateComponentSchema(Class<?> clazz) {
        if (clazz == null) return false;
        if (clazz.isArray()) return false;
        if (clazz.isPrimitive()) return false;
        if (clazz.isEnum()) return true;

        String className = clazz.getName();
        if (className.startsWith("java.lang.") && !className.equals("java.lang.Object")) return false;
        if (className.startsWith("java.util.") && !isCollectionOrMap(clazz)) return false;

        return !className.startsWith("java.") && !className.startsWith("javax.");
    }

    private static boolean isCollectionOrMap(Class<?> clazz) {
        return Collection.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz);
    }

    private static String getOrCreateComponentSchema(Class<?> clazz, Components components) {
        if (clazz == null) return "UnknownType";

        if (classToSchemaName.containsKey(clazz)) {
            return classToSchemaName.get(clazz);
        }

        String schemaName = generateSchemaName(clazz);

        // Prevent infinite recursion: put placeholder immediately
        classToSchemaName.put(clazz, schemaName);

        if (components.getSchemas() == null) {
            components.setSchemas(new HashMap<>());
        }

        // Also put an empty schema first (to break circular reference)
        Schema<?> placeholder = new Schema<>();
        placeholder.setType("object");
        components.getSchemas().put(schemaName, placeholder);

        // Now populate fields safely
        try {
            Schema<?> schema = new Schema<>();
            Set<Class<?>> visited = new HashSet<>();
            processFieldsSchema(clazz, schema, visited, components);

            // Replace placeholder with actual schema
            components.getSchemas().put(schemaName, schema);
            componentSchemas.put(schemaName, schema);
        } catch (Throwable t) {
            System.err.println("Error processing schema for " + clazz.getName() + ": " + t.getMessage());
        }

        return schemaName;
    }



    private static String generateSchemaName(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        String packageName = clazz.getPackage() != null ? clazz.getPackage().getName() : "";

        if (schemaNameCounter.containsKey(simpleName)) {
            boolean isSameClass = false;
            for (Map.Entry<Class<?>, String> entry : classToSchemaName.entrySet()) {
                if (entry.getValue().startsWith(simpleName) && entry.getKey().equals(clazz)) {
                    isSameClass = true;
                    break;
                }
            }

            if (!isSameClass) {
                int counter = schemaNameCounter.get(simpleName);
                schemaNameCounter.put(simpleName, counter + 1);

                return simpleName + "_" + counter;
            }
        } else {
            schemaNameCounter.put(simpleName, 1);
        }

        return simpleName;
    }

    private static void processFieldsSchema(Class<?> bodyClass, Schema<?> schema, Set<Class<?>> visited, Components components) {
        if (bodyClass == null || visited.contains(bodyClass)) {
            return;
        }
        visited.add(bodyClass);

        if (bodyClass.isArray()) {
            Class<?> componentType = bodyClass.getComponentType();
            schema.setType("array");
            Schema<?> itemsSchema = new Schema<>();
            if (shouldCreateComponentSchema(componentType)) {
                String schemaName = getOrCreateComponentSchema(componentType, components);
                itemsSchema.set$ref("#/components/schemas/" + schemaName);
            } else {
                processFieldsSchema(componentType, itemsSchema, visited, components);
            }
            schema.setItems(itemsSchema);
            return;
        }

        if (bodyClass.isEnum()) {
            schema.setType("string");
            List<String> enumValues = new ArrayList<>();
            for (Object enumConstant : bodyClass.getEnumConstants()) {
                enumValues.add(enumConstant.toString());
            }
            if (!enumValues.isEmpty()) {
                schema.setEnum(castList(enumValues));
            }
        } else if (isPrimitiveOrJavaType(bodyClass)) {
            setPrimitiveTypeSchema(bodyClass, schema);
        } else {
            schema.setType("object");
            Map<String, Schema> properties = new HashMap<>();
            schema.setProperties(properties);

            try {
                List<Field> allFields = getAllFields(bodyClass);
                for (Field field : allFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }

                    field.setAccessible(true);
                    String fieldName = field.getName();
                    Type fieldType = field.getGenericType();
                    Schema<?> fieldSchema = new Schema<>();

                    if (fieldType instanceof ParameterizedType) {
                        processParameterizedTypeSchema((ParameterizedType) fieldType, fieldSchema, visited, components);
                    } else if (field.getType().isArray()) {
                        Class<?> componentType = field.getType().getComponentType();
                        fieldSchema.setType("array");
                        Schema<?> itemsSchema = new Schema<>();
                        if (shouldCreateComponentSchema(componentType)) {
                            String schemaName = getOrCreateComponentSchema(componentType, components);
                            itemsSchema.set$ref("#/components/schemas/" + schemaName);
                        } else {
                            processFieldsSchema(componentType, itemsSchema, visited, components);
                        }
                        fieldSchema.setItems(itemsSchema);
                    } else {
                        if (shouldCreateComponentSchema(field.getType())) {
                            String schemaName = getOrCreateComponentSchema(field.getType(), components);
                            fieldSchema.set$ref("#/components/schemas/" + schemaName);
                        } else {
                            processFieldsSchema(field.getType(), fieldSchema, visited, components);
                        }
                    }
                    properties.put(fieldName, fieldSchema);
                }
            } catch (NoClassDefFoundError | Exception ex) {
                System.out.println("Warning: Error processing fields for " + bodyClass.getName() + ": " + ex.getMessage());
            }
        }

        visited.remove(bodyClass);
    }


    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(List<?> list) {
        return (List<T>) list;
    }

    private static void processParameterizedTypeSchema(ParameterizedType paramType, Schema<?> schema, Set<Class<?>> visited, Components components) {
        Type rawType = paramType.getRawType();

        if (rawType == List.class || rawType == Collection.class || rawType == Set.class || rawType == HashSet.class || rawType == LinkedList.class || rawType == ArrayList.class || rawType == TreeSet.class || rawType == LinkedHashSet.class) {
            schema.setType("array");
            Type elementType = paramType.getActualTypeArguments()[0];
            Schema<?> itemsSchema = createSchemaForType(elementType, components);
            schema.setItems(itemsSchema);
        } else if (rawType == Map.class || rawType == TreeMap.class || rawType == HashMap.class || rawType == LinkedHashMap.class) {
            schema.setType("object");
            Type[] typeArguments = paramType.getActualTypeArguments();
            Type valueType = typeArguments.length > 1 ? typeArguments[1] : null;
            Schema<?> additionalProps = createSchemaForType(valueType, components);
            schema.setAdditionalProperties(additionalProps);
        } else if (rawType instanceof Class<?>) {
            Class<?> rawClass = (Class<?>) rawType;
            if (shouldCreateComponentSchema(rawClass)) {
                String schemaName = getOrCreateComponentSchema(rawClass, components);
                schema.set$ref("#/components/schemas/" + schemaName);
            } else {
                processFieldsSchema(rawClass, schema, visited, components);
            }
        }
    }


    private static void processGenericTypeSchema(Type type, Schema<?> schema, Set<Class<?>> visited, Components components) {
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            if (shouldCreateComponentSchema(clazz)) {
                String schemaName = getOrCreateComponentSchema(clazz, components);
                schema.set$ref("#/components/schemas/" + schemaName);
            } else {
                processFieldsSchema(clazz, schema, visited, components);
            }
        } else if (type instanceof ParameterizedType) {
            processParameterizedTypeSchema((ParameterizedType) type, schema, visited, components);
        } else {
            schema.setType("object");
        }
    }

    // ===========================================
    // INLINE SCHEMA METHODS (for Parameters)
    // ===========================================

    private static Class<?> extractClassFromTypeInline(Type type, Schema<?> schema) {
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isArray()) {
                Class<?> componentType = clazz.getComponentType();
                Schema<?> items = new Schema<>();
                processFieldsSchemaInline(componentType, items, new HashSet<>());
                schema.setType("array");
                schema.setItems(items);
                return null;
            }
            return clazz;
        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type rawType = parameterizedType.getRawType();

            if (rawType == Optional.class) {
                Type wrappedType = parameterizedType.getActualTypeArguments()[0];
                return extractClassFromTypeInline(wrappedType, schema);
            }
            if (rawType == List.class || rawType == Collection.class || rawType == Set.class || rawType == HashSet.class || rawType == LinkedList.class || rawType == ArrayList.class || rawType == TreeSet.class || rawType == LinkedHashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                Schema<?> itemsJson = new Schema<>();
                processGenericTypeSchemaInline(elementType, itemsJson, new HashSet<>());
                schema.setType("array");
                schema.setItems(itemsJson);
                return null;
            } else if (rawType == Map.class || rawType == TreeMap.class || rawType == HashMap.class || rawType == LinkedHashMap.class) {
                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                Type valueType = typeArguments.length > 1 ? typeArguments[1] : null;
                Schema<?> nestedJson = new Schema<>();
                processGenericTypeSchemaInline(valueType, nestedJson, new HashSet<>());
                schema.setType("object");
                schema.setAdditionalProperties(nestedJson);
                return null;
            }

            return (Class<?>) rawType;
        }
        return null;
    }

    private static void processFieldsSchemaInline(Class<?> bodyClass, Schema<?> items, Set<Class<?>> visited) {
        if (bodyClass == null || visited.contains(bodyClass)) {
            return;
        }
        visited.add(bodyClass);
        if (bodyClass.isArray()) {
            Class<?> componentType = bodyClass.getComponentType();
            Schema<?> itemsJson = new Schema<>();
            processFieldsSchemaInline(componentType, itemsJson, visited);
            items.setType("array");
            items.setItems(itemsJson);
            return;
        }
        if(bodyClass.isEnum()){
            items.setType("string");
            Field[] declaredFields = bodyClass.getDeclaredFields();
            List<String> enumValues = new ArrayList<>();
            for (Field field : declaredFields) {
                if(field.isEnumConstant()) {
                    enumValues.add(field.getName());
                }
            }
            if(!enumValues.isEmpty()) {
                if(items.getEnum() == null){
                    items.setEnum(new ArrayList<>());
                }
                items.setEnum(castList(enumValues));
            }
        }
        else if (bodyClass.isPrimitive() || bodyClass.getName().startsWith("java.") ||
                bodyClass.getName().startsWith("javax.")) {
            setPrimitiveTypeSchema(bodyClass, items);
        } else {
            items.setType("object");
            Map<String, Schema> properties = new HashMap<>();
            items.setProperties(properties);
            try {
                List<Field> allFields = getAllFields(bodyClass);
                for (Field field : allFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    field.setAccessible(true);
                    String fieldName = field.getName();
                    Type fieldType = field.getGenericType();
                    Schema<?> fieldSchema = new Schema<>();
                    if (fieldType instanceof ParameterizedType) {
                        processParameterizedTypeSchemaInline((ParameterizedType) fieldType, fieldSchema, visited);
                    } else if (field.getType().isArray()) {
                        Class<?> componentType = field.getType().getComponentType();
                        Schema<?> itemsJson = new Schema<>();
                        processFieldsSchemaInline(componentType, itemsJson, visited);
                        fieldSchema.setType("array");
                        fieldSchema.setItems(itemsJson);
                    } else {
                        processFieldsSchemaInline(field.getType(), fieldSchema, visited);
                    }
                    properties.put(fieldName, fieldSchema);
                }
            } catch (NoClassDefFoundError | Exception ex) {
            }
        }
        visited.remove(bodyClass);
    }

    private static void processGenericTypeSchemaInline(Type type, Schema<?> jsonObject, Set<Class<?>> visited) {
        if (type instanceof Class<?>) {
            processFieldsSchemaInline((Class<?>) type, jsonObject, visited);
        } else if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type rawType = paramType.getRawType();
            if (rawType == Optional.class) {
                Type wrappedType = paramType.getActualTypeArguments()[0];
                processGenericTypeSchemaInline(wrappedType, jsonObject, visited);
                return;
            }
            if (rawType == List.class || rawType == Collection.class || rawType == Set.class || rawType == HashSet.class || rawType == LinkedList.class || rawType == ArrayList.class || rawType == TreeSet.class || rawType == LinkedHashSet.class) {
                Schema<?> itemsJson = new Schema<>();
                processGenericTypeSchemaInline(paramType.getActualTypeArguments()[0], itemsJson, visited);
                jsonObject.setType("array");
                jsonObject.setItems(itemsJson);
            } else if (rawType == Map.class || rawType == TreeMap.class || rawType == HashMap.class || rawType == LinkedHashMap.class) {
                Schema<?> additionalPropsJson = new Schema<>();
                Type[] typeArguments = paramType.getActualTypeArguments();
                processGenericTypeSchemaInline(typeArguments[1], additionalPropsJson, visited);
                jsonObject.setType("object");
                jsonObject.setAdditionalProperties(additionalPropsJson);
            } else {
                processFieldsSchemaInline((Class<?>) rawType, jsonObject, visited);
            }
        } else {
            jsonObject.setType("object");
        }
    }

    private static void processParameterizedTypeSchemaInline(ParameterizedType paramType, Schema<?> schema, Set<Class<?>> visited) {
        Type rawType = paramType.getRawType();

        if (rawType == List.class || rawType == Collection.class || rawType == Set.class || rawType == HashSet.class || rawType == LinkedList.class || rawType == ArrayList.class || rawType == TreeSet.class || rawType == LinkedHashSet.class) {
            schema.setType("array");
            Type elementType = paramType.getActualTypeArguments()[0];
            Schema<?> itemsJson = new Schema<>();
            processGenericTypeSchemaInline(elementType, itemsJson, visited);
            schema.setItems(itemsJson);
        } else if (rawType == Map.class || rawType == TreeMap.class || rawType == HashMap.class || rawType == LinkedHashMap.class) {
            schema.setType("object");
            Type[] typeArguments = paramType.getActualTypeArguments();
            Type valueType = typeArguments.length > 1 ? typeArguments[1] : null;
            Schema<?> additionalPropsJson = new Schema<>();
            processGenericTypeSchemaInline(valueType, additionalPropsJson, visited);
            schema.setAdditionalProperties(additionalPropsJson);
        } else if (rawType instanceof Class<?>) {
            processFieldsSchemaInline((Class<?>) rawType, schema, visited);
        }
    }

    // ===========================================
    // SHARED UTILITY METHODS
    // ===========================================

    private static boolean isPrimitiveOrJavaType(Class<?> clazz) {
        return clazz.isPrimitive() ||
                clazz.getName().startsWith("java.lang.") ||
                clazz.getName().startsWith("java.time.") ||
                clazz.getName().startsWith("java.util.") ||
                clazz.getName().startsWith("javax.");
    }

    private static void setPrimitiveTypeSchema(Class<?> clazz, Schema<?> schema) {
        String parameterType = clazz.getSimpleName().toLowerCase();

        if (parameterType.equals("int") || parameterType.equals("integer")) {
            schema.setType("integer");
        } else if (parameterType.equals("long")) {
            schema.setType("integer");
            schema.setFormat("int64");
        } else if (parameterType.equals("bigdecimal")) {
            schema.setType("number");
            schema.setFormat("double");
        } else if (parameterType.equals("short")) {
            schema.setType("integer");
            schema.setFormat("int32");
        } else if (parameterType.equals("localdate")) {
            schema.setType("string");
            schema.setFormat("date");
        } else if (parameterType.equals("localdatetime") || parameterType.equals("zoneddatetime") ||
                parameterType.equals("instant") || parameterType.equalsIgnoreCase("datetimeformatter")) {
            schema.setType("string");
            schema.setFormat("date-time");
        } else if (parameterType.equalsIgnoreCase("zoneid")) {
            schema.setType("string");
            schema.setFormat("timezone");
        } else if (parameterType.equalsIgnoreCase("pattern")) {
            schema.setType("string");
            schema.setFormat("regex");
        } else if (parameterType.equals("float")) {
            schema.setType("number");
            schema.setFormat("float");
        } else if (parameterType.equals("double")) {
            schema.setType("number");
            schema.setFormat("double");
        } else if (parameterType.equals("date")) {
            schema.setType("string");
            schema.setFormat("date-time");
        } else if (parameterType.equals("byte")) {
            schema.setType("string");
            schema.setFormat("byte");
        } else if (parameterType.equals("boolean")) {
            schema.setType("boolean");
        } else if (parameterType.equals("char") || parameterType.equals("character")) {
            schema.setType("string");
        } else if (parameterType.equalsIgnoreCase("httpheaders") || parameterType.equalsIgnoreCase("path") ||
                parameterType.equalsIgnoreCase("hashmap") || parameterType.equalsIgnoreCase("map") ) {
            schema.setType("object");
        } else if (parameterType.equals("multipartfile") || parameterType.equals("file")) {
            schema.setType("string");
            schema.setFormat("binary");
        } else if (parameterType.equalsIgnoreCase("void")) {
            // No type for void
        } else if (parameterType.equals("string")) {
            schema.setType("string");
        }
        else if (parameterType.equals("list")) {
            schema.setType("array");
            schema.setItems(new Schema<>());
        }else {
            schema.setType(parameterType);
        }
    }

    private static String getMappingUrl(Class<?> controller, Method method) {
        String baseUrl = "";
        if (controller.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping annotation = controller.getAnnotation(RequestMapping.class);
            baseUrl = annotation.value().length > 0 ? annotation.value()[0] : "";
        }
        if(Objects.equals(baseUrl, "/")){
            baseUrl ="";
        }

        if (method.isAnnotationPresent(GetMapping.class)) {
            GetMapping annotation = method.getAnnotation(GetMapping.class);
            return baseUrl + (annotation.value().length > 0 ? annotation.value()[0] : "");
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            PostMapping annotation = method.getAnnotation(PostMapping.class);
            return baseUrl + (annotation.value().length > 0 ? annotation.value()[0] : "");
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            PutMapping annotation = method.getAnnotation(PutMapping.class);
            return baseUrl + (annotation.value().length > 0 ? annotation.value()[0] : "");
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            DeleteMapping annotation = method.getAnnotation(DeleteMapping.class);
            return baseUrl + (annotation.value().length > 0 ? annotation.value()[0] : "");
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            PatchMapping annotation = method.getAnnotation(PatchMapping.class);
            return baseUrl + (annotation.value().length > 0 ? annotation.value()[0] : "");
        } else if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping annotation = method.getAnnotation(RequestMapping.class);
            return baseUrl + (annotation.value().length > 0 ? annotation.value()[0] : "");
        }
        return null;
    }


    private static ApiResponses extractResponseBodies(Method method, Components components) throws JsonProcessingException {
        ApiResponses responses = new ApiResponses();
        ApiResponse response = new ApiResponse();
        response.setDescription("");
        Content content = new Content();
        MediaType mediaType = null;
        Schema<?> schema = new Schema<>();
        Class<?> returnType = method.getReturnType();
        if (!returnType.equals(void.class) && !returnType.equals(Void.class)) {
            mediaType = new MediaType();
            if (returnType.getName().contains("ResponseEntity")) {
                Type genericReturnType = method.getGenericReturnType();
                if (genericReturnType instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericReturnType;
                    Type[] typeArgs = paramType.getActualTypeArguments();
                    if (typeArgs.length > 0) {
                        Type actualType = typeArgs[0];
                        String typeName = actualType.getTypeName();
                        if (typeName.equals("?")) {
                            schema = new ObjectSchema();
                            mediaType.setSchema(schema);
                        } else if (typeName.equalsIgnoreCase("void")) {
                            schema = new Schema<>();
                            mediaType.setSchema(schema);
                        } else {
                            schema = getSchemaObject(actualType, components);
                            mediaType.setSchema(schema);
                        }
                    }
                }
            } else {
                schema = getBodyFieldsSchema(method.getGenericReturnType(), components);
                mediaType.setSchema(schema);
            }
        }
        content.put("*/*",mediaType);
        response.setContent(content);
        responses.put("200",response);
        return responses;
    }

    private static void findTargetClasses(File dir, List<URL> urls) throws MalformedURLException {
        if (!dir.exists()) return;

        File targetClasses = new File(dir, "target/classes");
        if (targetClasses.exists()) {
            urls.add(targetClasses.toURI().toURL());
        }

        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                if (subDir.getName().startsWith(".") ||
                        subDir.getName().equals("target") ||
                        subDir.getName().equals("node_modules")) {
                    continue;
                }
                findTargetClasses(subDir, urls);
            }
        }
    }

    public static String parsePath(String uri, Map<String, String> patterns) {
        if (uri == null) {
            return null;
        } else if (StringUtils.isBlank(uri)) {
            return String.valueOf('/');
        } else {
            CharacterIterator ci = new StringCharacterIterator(uri);
            StringBuilder pathBuffer = new StringBuilder();
            char c = ci.first();
            if (c == '\uffff') {
                return String.valueOf('/');
            } else {
                do {
                    if (c == '{') {
                        String regexBuffer = cutParameter(ci, patterns);
                        if (regexBuffer == null) {
                            return null;
                        }

                        pathBuffer.append(regexBuffer);
                    } else {
                        int length = pathBuffer.length();
                        if (c != '/' || length == 0 || pathBuffer.charAt(length - 1) != '/') {
                            pathBuffer.append(c);
                        }
                    }
                } while((c = ci.next()) != '\uffff');

                return pathBuffer.toString();
            }
        }
    }

    private static String cutParameter(CharacterIterator ci, Map<String, String> patterns) {
        StringBuilder regexBuffer = new StringBuilder();
        int braceCount = 1;

        for(char regexChar = ci.next(); regexChar != '\uffff'; regexChar = ci.next()) {
            if (regexChar == '{') {
                ++braceCount;
            } else if (regexChar == '}') {
                --braceCount;
                if (braceCount == 0) {
                    break;
                }
            }

            regexBuffer.append(regexChar);
        }

        if (braceCount != 0) {
            return null;
        } else {
            String regex = StringUtils.trimToNull(regexBuffer.toString());
            if (regex == null) {
                return null;
            } else {
                StringBuilder pathBuffer = new StringBuilder();
                pathBuffer.append('{');
                int index = regex.indexOf(58);
                if (index != -1) {
                    String name = StringUtils.trimToNull(regex.substring(0, index));
                    String value = StringUtils.trimToNull(regex.substring(index + 1, regex.length()));
                    if (name == null) {
                        return null;
                    }

                    pathBuffer.append(name);
                    if (value != null) {
                        patterns.put(name, value);
                    }
                } else {
                    pathBuffer.append(regex);
                }

                pathBuffer.append('}');
                return pathBuffer.toString();
            }
        }
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;

        while (currentClass != null && currentClass != Object.class) {
            Field[] declaredFields = currentClass.getDeclaredFields();
            for (Field field : declaredFields) {
                boolean isDuplicate = false;
                for (Field existingField : fields) {
                    if (existingField.getName().equals(field.getName())) {
                        isDuplicate = true;
                        break;
                    }
                }
                if (!isDuplicate) {
                    fields.add(field);
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return fields;
    }
}