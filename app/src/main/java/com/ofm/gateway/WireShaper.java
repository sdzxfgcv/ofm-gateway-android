package com.ofm.gateway;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Mirrors src/o2a toolshape.js + official-prompt.js + gateway-body.js:
 * inject the official system prompt, inject the 13 literal official tools,
 * rename caller tools, drop tool_choice:'none', force usage + completion limit.
 */
public final class WireShaper {

    public static final String PROMPT_MARKER = "You are assisting with a user's software project through OFM.";
    public static final String SKILLS = "Use only skills explicitly provided by the host; do not assume built-in skills are available.";
    public static final String CLIENT_TOOL_PREFIX = "clint_";

    private static final String[] ORDER = {
            "edit", "execute", "glob", "grep", "question", "read", "shell",
            "skill", "subagent", "webfetch", "websearch", "write", "patch"
    };

    private WireShaper() {}

    public static String shape(String rawBody, String agentContextText, String contractsJson,
                               String workDir, String model) {
        try {
            return shapeInner(rawBody, agentContextText, contractsJson, workDir, model);
        } catch (Exception e) {
            return rawBody; // fail-open: pass through untouched
        }
    }

    private static String shapeInner(String rawBody, String agentContextText, String contractsJson,
                                     String workDir, String model) throws JSONException {
        JSONObject body = new JSONObject(rawBody);

        injectOfficialPrompt(body, agentContextText, workDir, model);
        renameAndCompleteTools(body, contractsJson);

        // v2: tool_choice:'none' is 403. Drop it.
        if ("none".equals(body.optString("tool_choice", ""))) body.remove("tool_choice");

        boolean isChat = !body.has("wire") || "chat".equals(body.optString("wire"));
        if (isChat) {
            if (body.optBoolean("stream", false)) {
                Object so = body.opt("stream_options");
                JSONObject opt = (so instanceof JSONObject) ? (JSONObject) so : new JSONObject();
                opt.put("include_usage", true);
                body.put("stream_options", opt);
            }
            if (!body.has("max_tokens") && !body.has("max_completion_tokens")) {
                body.put("max_completion_tokens", 32000);
            }
        }
        return body.toString();
    }

    private static void injectOfficialPrompt(JSONObject body, String agentContextText,
                                             String workDir, String model) throws JSONException {
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null) return;
        String id = String.valueOf(model == null ? body.optString("model", "") : model);
        if (id.startsWith("opencode/")) id = id.substring("opencode/".length());
        if (id.trim().isEmpty()) id = "unknown";
        if (workDir == null || workDir.isEmpty()) workDir = "/sdcard";

        String env = "Model: " + id + "\n<env>\n  Working directory: " + workDir
                + "\n  Workspace root folder: " + workDir
                + "\n  Platform: linux\n  Today's date: " + Fingerprint.utcDate() + "\n</env>";

        String prompt = (agentContextText == null ? "" : agentContextText.trim())
                + "\n" + env + "\n" + SKILLS;
        if (prompt.trim().isEmpty()) return;

        int anchor = -1;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if (!"system".equals(role) && !"developer".equals(role)) continue;
            Object c = m.opt("content");
            if (c instanceof String && ((String) c).contains(PROMPT_MARKER)) return;
            if (anchor < 0) anchor = i;
        }
        if (anchor < 0) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", prompt);
            messages.put(0, sys);
        } else {
            JSONObject m = messages.getJSONObject(anchor);
            Object c = m.opt("content");
            String prior = (c instanceof String) ? ((String) c) : "";
            m.put("content", prior.isEmpty() ? prompt : (prompt + "\n\n" + prior));
        }
    }

    private static void renameAndCompleteTools(JSONObject body, String contractsJson) throws JSONException {
        JSONObject contracts;
        try {
            contracts = new JSONObject(contractsJson == null ? "{}" : contractsJson);
        } catch (Exception e) {
            contracts = new JSONObject();
        }

        JSONArray tools = body.optJSONArray("tools");
        if (tools == null) {
            tools = new JSONArray();
            body.put("tools", tools);
        }

        java.util.Set<String> officialNames = new java.util.HashSet<>(java.util.Arrays.asList(ORDER));
        java.util.Set<String> present = new java.util.HashSet<>();

        for (int i = 0; i < tools.length(); i++) {
            JSONObject entry = tools.optJSONObject(i);
            if (entry == null) continue;
            JSONObject fn = entry.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name", "");
            if (name.isEmpty()) continue;
            if (officialNames.contains(name)) {
                present.add(name);
                continue;
            }
            String target = officialForClientName(name);
            if (target != null && !present.contains(target)) {
                present.add(target);
                fn.put("name", target);
            } else {
                fn.put("name", CLIENT_TOOL_PREFIX + Fingerprint.sha256Hex(name).substring(0, 12));
            }
        }

        for (String name : ORDER) {
            if (present.contains(name)) continue;
            JSONObject def = contracts.optJSONObject(name);
            String description = def == null ? ("Agent tool " + name) : def.optString("description", "Agent tool " + name);
            Object parameters = def == null ? new JSONObject() : def.opt("parameters");
            if (parameters == null) parameters = new JSONObject();
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("description", description);
            fn.put("parameters", parameters);
            fn.put("strict", false);
            JSONObject entry = new JSONObject();
            entry.put("type", "function");
            entry.put("function", fn);
            tools.put(entry);
        }
    }

    private static String officialForClientName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("bash") || lower.contains("shell") || lower.contains("execute")) return "shell";
        if (lower.contains("read") || lower.contains("cat") || lower.contains("view")) return "read";
        if (lower.contains("write") || lower.contains("edit") || lower.contains("patch")) return "edit";
        if (lower.contains("grep") || lower.contains("search")) return "grep";
        if (lower.contains("glob") || lower.contains("list")) return "glob";
        return null;
    }
}
