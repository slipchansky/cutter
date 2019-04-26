package com.stas;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.stas.CutParameters.Replacements;
import com.stas.CutParameters.Replacement;

import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

public class SpecCutter {

    private static final String DEFINITIONS_PREFIX = "#/definitions/";
    private final static Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");
    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static {
        OBJECT_MAPPER.configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    @Data @AllArgsConstructor @EqualsAndHashCode
    private static class SpecMethod {
        private Set<String> methods;
        private String path;
    }

    private Set<SpecMethod> requiredMethods = new HashSet<>();
    private Set<String> requiredTypes = new HashSet<>();
    private Map spec;
    private CutParameters parameters;
    private String cutFileName;
    
    boolean preparecut = false;
    private boolean toFile;
    
    public SpecCutter(String[] args) {
        if (args.length < 1) {
            System.out.println("Use cutter <cut-parameters>.cut");
            System.exit (0);
        }
        cutFileName = args [0];
        File cutFile = new File (cutFileName);
        
        if (!cutFile.exists()) {
            System.out.println("Can't find file "+cutFileName);
            System.exit (0);
        }
        
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            parameters = mapper.readValue(cutFile, CutParameters.class);
        } catch (IOException e) {
            parameters = new CutParameters ();
            parameters.setInput(args[0]);
            if (args.length > 1) {
                parameters.setOutput(args[1]);
            } else {
                parameters.setOutput("console");
            }
        }
        
    }
    
    
    private String readFile(InputStream in) {
        String content = new BufferedReader(new InputStreamReader(in))
        .lines().collect(Collectors.joining("\n"));
        content = applyReplacements(content);
        return content; 
    }


    private String applyReplacements(String content) {
        Replacements replace = parameters.getReplace();
        if (replace != null) {
            for (Replacement r : replace) {
               content = content.replace(r.getSample(), r.getReplacement());
            }
        }
        return content;
    }    


    private void readSpec(String specPath) {
        
        
        URL specUrl = null;
        if (!new File(specPath).exists()) {
            try {
                specUrl = new URL(specPath);
            } catch (MalformedURLException e) {
                System.out.println("Can't open spec at "+specPath);
                System.exit(0);
            }
        } else {
            try {
                specUrl = new File(specPath).toURI().toURL();
            } catch (MalformedURLException e) {
                System.out.println("Can't open spec at "+specPath);
                System.exit(0);
            }
        }
        
        try (InputStream in = specUrl.openStream()) {
        
            String content = readFile(in);
            spec = OBJECT_MAPPER.readValue (content, Map.class);
        } catch (IOException e) {
            System.out.println("Can't open spec at "+specPath);
            System.exit(0);
        }
        
    }
    
    
    public void cutIt () {
        String specPath = parameters.getInput();
        readSpec(specPath);
        if (parameters.getOutput().endsWith (".cut")) {
          compileCut ();
        } else {
            readRequiredMethods();
            process ();
        }
    }


    private void compileCut() {
        // TODO Auto-generated method stub
        Map<String, Object> paths = (Map<String, Object>)spec.get("paths");
        Set<Entry<String, Object>> entries = paths.entrySet();
        Set<String> operationIds = new HashSet<> ();
         
        for (Entry<String, Object> e : entries) {
            String path = e.getKey();
            Map<String, Object> mMethods = (Map<String, Object> )e.getValue();
            String ms = String.join(",", mMethods.keySet()).toLowerCase();
            String mm = ms+":"+path;
            parameters.getMethods().add(mm);
        }
        
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (OutputStream out = getOutputStream()) {
            parameters.setOutput("console");
            parameters.getReplace().add(new Replacement());
            mapper.writeValue(out, parameters);
        } catch (IOException e1) {
            System.out.println("Error writing cut file to "+parameters.getOutput());
            System.exit (0);
        }
        
        
    }


    private void process() {
        if (parameters.getMethods().size() > 0) {
            extractRequiredDefinitions();
            killExtra ();
        }
        normalizeParameters ();
        writeResult ();
    }


    private void writeResult() {
        OutputStream out = getOutputStream();
        ObjectMapper mapper = OBJECT_MAPPER;
        
        String name = parameters.getOutput();
        if (name!=null && (name.toLowerCase().endsWith(".yaml")|| name.toLowerCase().endsWith(".yml"))) {
            mapper = new ObjectMapper(new YAMLFactory());
        }
        
        try {
            mapper.writeValue(out, spec);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        if (toFile) {
            System.out.println("Complete");
        }
        
    }


    private OutputStream getOutputStream() {
        OutputStream out = System.out;
        String outputFileName = parameters.getOutput();
        toFile = false;
        if (outputFileName != null && !outputFileName.toLowerCase().startsWith("console")) {
            try {
                
                if (".".equals(outputFileName)) {
                    outputFileName = cutFileName.replace (".cut", ".json");
                }
                
                out = new FileOutputStream (outputFileName);
                System.out.println("Write excerption to "+outputFileName);
                toFile = true;
            } catch (FileNotFoundException e) {
                System.out.println("Can't open/create output file "+outputFileName);
                System.exit(0);
            }
        
        }
        return out;
    }


    private void normalizeParameters() {
        Map<String, Object> paths = (Map<String, Object>)spec.get("paths");
        Set<Entry<String, Object>> entries = paths.entrySet();
        Set<String> operationIds = new HashSet<> (); 
        
        for (Entry<String, Object> e : entries) {
            String path = e.getKey();
            Map<String, Object> mMethods = (Map<String, Object> )e.getValue();
            Set<Entry<String, Object>> methods = mMethods.entrySet();
            for (Entry<String, Object> m : methods) {
                Map method = (Map)m.getValue();
                String operationId = (String)method.get("operationId");
                operationId = uniquefy (operationIds, operationId);
                method.put("operationId", operationId);
                
                Collection params = (Collection)(method).get("parameters");
                if (params == null) continue;
                Map goodparams = new LinkedHashMap ();
                for (Object op : params) {
                    Map<String, Object> p = (Map<String, Object>) op;
                    String name = (String) p.get("name");
                    if (path.indexOf('{'+name+'}')>=0) {
                        p.put ("in", "path");
                    }
                    if (p.get("type")==null && p.get("schema")==null) p.put("type", "string");
                    if ("int".equals(p.get("type"))) p.put ("type", "integer");
                    
                    
                    if (goodparams.containsKey(name)) {
                        Map<String, Object> cp = (Map<String, Object>)goodparams.get (name);
                        if (cp.get("description")==null && p.get("description")!=null) {
                            goodparams.put(name, cp);
                        }
                    } else {
                        goodparams.put(name, p);
                    }
                }
                
                checkAndUpdatePathParams (goodparams, path);
                
                params = goodparams.values();
                ((Map)m.getValue()).put("parameters", params);
            }
        }
    }


    private void checkAndUpdatePathParams(Map<String, Object> goodparams, String path) {
        Matcher matcher = PATH_PARAM_PATTERN.matcher(path);
        
        while (matcher.find()) {
                String pathParam = matcher.group(1);
                if (!goodparams.containsKey(pathParam)) {
                    Map<String, Object> pathParamDefinition = new LinkedHashMap<> ();
                    pathParamDefinition.put("name", pathParam);
                    pathParamDefinition.put("in", "path");
                    pathParamDefinition.put("required", true);
                    pathParamDefinition.put("type", "string");
                    goodparams.put(pathParam, pathParamDefinition);
                    
                }
        }
        
    }


    private String uniquefy(Set<String> operationIds, String operationId) {
        for (;operationIds.contains(operationId);) {
            operationId = operationId+"_";
        }
        operationIds.add (operationId);
        return operationId;
    }


    private void extractRequiredDefinitions() {
        for (SpecMethod m : requiredMethods) {
            for (String hm : m.getMethods()) {
                Map methodSpec = findMethodSpec (hm, m.path);
                if (methodSpec != null) {
                    extractRequiredTypes (methodSpec);
                }
            }
        }
    }


    private void killExtra() {
        killExtraPaths();
        killExtraMethods();
        killExtraDefinitions();
    }


    private void killExtraDefinitions() {
        Map<String, Object> definitions = (Map)spec.get("definitions");
        Set<String> extraDefs = definitions.keySet().stream().filter (d -> !requiredTypes.contains (d)).collect(Collectors.toSet());
        for (String d : extraDefs) {
            definitions.remove(d);
        }
    }


    private void killExtraMethods() {
        Map<Object, Object> paths = (Map)spec.get ("paths");
        for (SpecMethod m : requiredMethods) {
            Map<Object, Object> pathSpec = (Map)paths.get(m.getPath());
            Set<String> extraMethods = pathSpec.keySet().stream().filter(p -> !m.getMethods().contains(p)).map(String::valueOf).collect(Collectors.toSet());
            for (String em : extraMethods) pathSpec.remove(em);
        }
    }


    private void killExtraPaths() {
        Map<Object, Object> paths = (Map)spec.get ("paths");
        Set<String> requiredPaths = requiredMethods.stream().map(SpecMethod::getPath).collect(Collectors.toSet());
        Set<String> extraPaths = paths.keySet().stream().filter(p -> !requiredPaths.contains(p)).map(String::valueOf).collect(Collectors.toSet());
        for (String p : extraPaths) {
            paths.remove(p);
        }
    }


    private void extractRequiredTypes(Map<Object, Object> spec) {
        
        spec.entrySet().stream().forEach (e -> {
            if (e.getValue () instanceof Collection) {
                extractRequiredTypes((Collection)e.getValue());
            } 
            else if (e.getValue () instanceof Map) {
                extractRequiredTypes((Map)e.getValue());
            }
            else {
                if ("$ref".equals (e.getKey()) && e.getValue() instanceof String) {
                    String value = (String)e.getValue();
                    if ( value.startsWith(DEFINITIONS_PREFIX)) {
                        String requiredType = value.substring(DEFINITIONS_PREFIX.length());
                             addDefinition(requiredType);
                    }
                }
            }
        });
        
    }


    private void addDefinition(String requiredType) {
        if (!requiredTypes.contains(requiredType)) {
            requiredTypes.add(requiredType);
            Map<String, Object> definitions = (Map)this.spec.get("definitions");
            Map typeSpec = (Map)definitions.get(requiredType);
            extractRequiredTypes (typeSpec);
        }
        
        
    }


    private void extractRequiredTypes(Collection collection) {
        
        for (Object o : collection) {
            if (o instanceof Map) {
                extractRequiredTypes((Map)o);
            }
        }
        
    }


    private Map findMethodSpec(String m, String p) {
        Map paths = (Map)spec.get ("paths");
        
        Map path = (Map)paths.get(p);
        if (path == null) {
            System.out.println("Path not found "+p);
            System.exit(0);
        }
        Map pathSpec = (Map)path.get(m);
        if (pathSpec==null) {
            return null;
        }
        return pathSpec;
    }


    private void readRequiredMethods() {
        for (String path : parameters.getMethods()) {
            path = applyReplacements(path);
            String a[] = path.split(":", 2);
            Set<String> methods = new HashSet<> ();
            methods.add ("get");
            if (a.length==2) {
                String sMethods = a[0].toLowerCase();
                methods = Stream.of (sMethods.split(",")).collect(Collectors.toSet());
                path = a[1];
            }
            SpecMethod m = new SpecMethod (methods, path);
            requiredMethods.add(m);
        }
    }
    
    
    public static void main(String[] args) {
        SpecCutter cutter = new SpecCutter (args);
        cutter.cutIt ();
    }

}
