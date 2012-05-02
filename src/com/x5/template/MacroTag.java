package com.x5.template;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.x5.util.LiteXml;

import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
//import net.minidev.json.parser.ParseException;

public class MacroTag extends BlockTag
{
    private String templateRef;
    private Map<String,Object> macroDefs;

    public static final String MACRO_MARKER = "exec";
    public static final String MACRO_END_MARKER = "/exec";
    
    // macro can take args in many formats
    private static final String FMT_CHUNK       = "chunk";
    private static final String FMT_XML         = "xml";
    private static final String FMT_JSON_LAX    = "json";
    private static final String FMT_JSON_STRICT = "json-strict";
    private static final String FMT_ORIGINAL    = "original";
    
    public MacroTag()
    {
    }
    
    public MacroTag(String tagName, Snippet body)
    {
        templateRef = tagName.substring(MACRO_MARKER.length()+2).trim();
        
        String defFormat = FMT_ORIGINAL; // chunk is the standard format
        // check for nonstandard requested format
        int spacePos = templateRef.indexOf(' ');
        if (spacePos > 0) {
            defFormat = templateRef.substring(spacePos+1).toLowerCase();
            templateRef = templateRef.substring(0,spacePos);
        }
        
        parseDefs(body,defFormat);
    }
    
    private void parseDefs(Snippet body, String defFormat)
    {
        if (defFormat.equals(FMT_ORIGINAL)) {
            parseDefsOriginal(body);
        } else if (defFormat.equals(FMT_CHUNK)) {
            parseDefsSimplified(body);
        } else if (defFormat.equals(FMT_JSON_STRICT)) {
            parseDefsJsonStrict(body);
        } else if (defFormat.equals(FMT_JSON_LAX)) {
            parseDefsJsonLax(body);
        } else if (defFormat.equals(FMT_XML)) {
            parseDefsXML(body);
        }
    }
    
    private void parseDefsJsonLax(Snippet body)
    {
        String json = body.toString();
        JSONObject defs = (JSONObject)JSONValue.parse(json);
        importJSONDefs(defs);
    }
    
    private void parseDefsJsonStrict(Snippet body)
    {
        try {
            String json = body.toString();
            JSONObject defs = (JSONObject)JSONValue.parseStrict(json);
            importJSONDefs(defs);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
    
    private void importJSONDefs(JSONObject defs)
    {
        this.macroDefs = (Map<String,Object>)defs;
    }
    
    private void parseDefsSimplified(Snippet body)
    {
        // TODO
    }
    
    private void parseDefsXML(Snippet body)
    {
        LiteXml xml = new LiteXml(body.toString());
        this.macroDefs = parseXMLObject(xml);
    }
    
    private Map<String,Object> parseXMLObject(LiteXml xml)
    {
        LiteXml[] rules = xml.getChildNodes();
        if (rules == null) {
            return null;
        } else {
            Map<String,Object> tags = new HashMap<String,Object>();
            
            for (LiteXml rule : rules) {
                String tagName = rule.getNodeType();
                
                // check for nested object
                LiteXml[] children = rule.getChildNodes();
                
                if (children == null) {
                    tags.put(tagName, rule.getNodeValue());
                } else {
                    tags.put(tagName, parseXMLObject(rule));
                }
            }
            
            return tags;
        }
    }
    
    private void parseDefsOriginal(Snippet body)
    {
        List<SnippetPart> parts = body.getParts();
        
        for (int i=0; i<parts.size(); i++) {
            // seek until a tag definition {~tag_def=} is found
            SnippetPart part = parts.get(i);
            if (part.isTag()) {
                String tagText = ((SnippetTag)part).getTag();
                if (tagText.trim().endsWith("=")) {
                    int j = findMatchingDefEnd(parts,i+1);
                    Snippet def = new Snippet(parts,i+1,j);
                    String varName = tagText.substring(0,tagText.length()-1);
                    saveDef(varName,def);
                    // skip to next def
                    i = j;
                    
                    if (j < parts.size()) {
                        SnippetPart partJ = parts.get(j);
                        if (partJ.getText().equals("{=}")) {
                            // skip over endDef tag
                            i = j+1;
                        }
                    }
                } else {
                    // some vars are defined simply, like so {~name=Bob} or {~name = Bob}
                    String[] simpleDef = getSimpleDef(tagText);
                    if (simpleDef != null) {
                        saveDef(simpleDef[0],simpleDef[1]);
                    }
                }
            }
        }
    }
    
    private int findMatchingDefEnd(List<SnippetPart> parts, int startAt)
    {
        // find next {=} defEnd tag or next defBegin-style tag
        // which is NOT inside a nested macroDef.
        
        // actually any nested macrodef tags shouldn't be at this level
        // since Snippet.groupBlocks helpfully tucks them away.
        
        // default is to eat entire rest of block
        int allTheRest = parts.size();
        
        for (int i=startAt; i<allTheRest; i++) {
            SnippetPart part = parts.get(i);
            if (part.isTag()) {
                String tagText = ((SnippetTag)part).getTag();
                int eqPos = tagText.indexOf('=');
                if (eqPos < 0) continue;
                if (tagText.length() == 1) return i; // found explicit def-cap {=}
                
                // we're good as long as ".|:(" do not appear to the left of the =
                char[] tagChars = tagText.toCharArray();
                char c = '=';
                for (int x=0; x<eqPos; x++) {
                    c = tagChars[x];
                    if (c == '.' || c == '|' || c == ':' || c == '(') {
                        // signal failure
                        c = 0;
                        break;
                    }
                }
                if (c == 0) {
                    continue; // fail, keep looking
                } else {
                    // made it to the = safely!  this is the next assignment, close def off
                    return i;
                }
            }
        }
        
        return allTheRest;
    }
    
    private String[] getSimpleDef(String tagText)
    {
        int eqPos = tagText.indexOf('=');
        if (eqPos > -1) {
            String varName = tagText.substring(0,eqPos).trim();
            String varValue = tagText.substring(eqPos+1);
            // trim this: {~name = Bob } but not this {~name= Bob}
            if (varValue.charAt(0) == ' ') {
                if (tagText.charAt(eqPos-1) == ' ') {
                    varValue = varValue.trim();
                }
            }
            String[] assignment = new String[]{varName,varValue};
            return assignment;
        } else {
            return null;
        }
    }
    
    private void saveDef(String tag, String def)
    {
        if (tag == null || def == null) return;
        Snippet simple = Snippet.getSnippet(def);
        saveDef(tag,simple);
    }
    
    private void saveDef(String tag, Snippet snippet)
    {
        if (tag == null || snippet == null) return;
        if (macroDefs == null) macroDefs = new HashMap<String,Object>();
        macroDefs.put(tag, snippet);
    }

    public void renderBlock(Writer out, Chunk context, int depth)
        throws IOException
    {
        Chunk macro = context.getChunkFactory().makeChunk(templateRef);
        macro.setMultiple(macroDefs);
        
        macro.render(out, context);
    }

    public String getBlockStartMarker()
    {
        return MACRO_MARKER;
    }

    public String getBlockEndMarker()
    {
        return MACRO_END_MARKER;
    }

}