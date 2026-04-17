package dash.web;

import dash.FabricDash;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Collectors;

public class PluginsPage {

    public static String render() {
        boolean canManagePlugins = HtmlTemplate.can("dash.web.plugins.manage");

        StringBuilder pluginsHtml = new StringBuilder();

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            boolean enabled = true;
            String name = mod.getMetadata().getName();
            String version = mod.getMetadata().getVersion().getFriendlyString();
            String authors = mod.getMetadata().getAuthors().stream().map(Person::getName).collect(Collectors.joining(", "));
            String description = mod.getMetadata().getDescription();
            if (description == null)
                description = "No description available";
            if (description.length() > 100)
                description = description.substring(0, 100) + "...";

            pluginsHtml.append(
                    "<div class=\"group p-4 rounded-xl bg-white/5 hover:bg-white/10 transition-colors border border-transparent hover:border-white/10\">\n")
                    .append("<div class=\"flex items-start justify-between\">\n")
                    .append("<div class=\"flex items-center gap-3\">\n")
                    .append("<div class=\"h-10 w-10 rounded-xl ")
                    .append(enabled ? "bg-emerald-500/20" : "bg-rose-500/20")
                    .append(" flex items-center justify-center\">\n")
                    .append("<span class=\"material-symbols-outlined text-[20px] ")
                    .append(enabled ? "text-emerald-400" : "text-rose-400").append("\">view_module</span>\n")
                    .append("</div>\n")
                    .append("<div>\n")
                    .append("<div class=\"flex items-center gap-2\">\n")
                    .append("<p class=\"text-white font-semibold\">").append(name).append("</p>\n")
                    .append("<span class=\"px-2 py-0.5 rounded-full bg-slate-700 text-slate-300 text-xs font-mono\">v")
                    .append(version).append("</span>\n")
                    .append("</div>\n")
                    .append("<p class=\"text-slate-500 text-sm\">")
                    .append(authors.isEmpty() ? "Unknown author" : "by " + authors).append("</p>\n")
                    .append("</div>\n")
                    .append("</div>\n")
                    .append("<div class=\"flex items-center gap-2\">\n");

            if (canManagePlugins) {
                String modJar = getModJarFileName(mod);
                pluginsHtml.append("<span class=\"px-3 py-1 rounded-lg text-xs font-semibold bg-emerald-500/20 text-emerald-400\">Loaded</span>\n");

                if (modJar != null && !modJar.isBlank()) {
                    pluginsHtml.append("<form action='/action' method='post' class='inline' onsubmit=\"return confirm('Delete mod file? Restart required.');\">\n")
                            .append("<input type='hidden' name='action' value='plugin_delete'>\n")
                            .append("<input type='hidden' name='plugin' value='").append(name).append("'>\n")
                            .append("<input type='hidden' name='plugin_file' value='").append(modJar).append("'>\n")
                            .append("<button class=\"px-3 py-1 rounded-lg text-xs font-semibold bg-rose-500/20 text-rose-300 hover:bg-rose-500/30 transition-colors\">Delete File</button>\n")
                            .append("</form>\n");
                }
            }

            pluginsHtml.append("</div>\n")
                    .append("</div>\n")
                    .append("<p class=\"mt-2 text-slate-400 text-sm\">").append(description).append("</p>\n")
                    .append("</div>\n");
        }

        int totalCount = (int) FabricLoader.getInstance().getAllMods().stream().count();
        int enabledCount = totalCount;

        String uploadScript = canManagePlugins
                ? "document.getElementById('plugin-upload').addEventListener('change', e => {\n" +
                        "  const file = e.target.files[0];\n" +
                        "  if (!file) return;\n" +
                        "  if (!file.name.endsWith('.jar')) { showToast('Only .jar files allowed', 'error'); return; }\n" +
                        "  const formData = new FormData();\n" +
                        "  formData.append('file', file);\n" +
                        "  fetch('/api/upload/plugin', {method:'POST', body: formData}).then(r => r.json()).then(d => {\n"
                        +
                        "    if (d.success) { showToast('Mod uploaded! Restart server to load.', 'success'); if(window.dashNavigate){dashNavigate(location.pathname+location.search,false);} }\n"
                        +
                        "    else showToast('Error: ' + d.error, 'error');\n" +
                        "  });\n" +
                        "});\n"
                : "";

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 min-w-0 p-6\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">view_module</span>\n" +
                "<h2 class=\"text-lg font-bold text-white\">Installed Mods</h2>\n" +
                "<span class=\"px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 text-xs font-mono\">"
                + enabledCount + "/" + totalCount + " loaded</span>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<input type=\"text\" id=\"plugin-search\" placeholder=\"Search mods...\" class=\"bg-slate-800 border border-slate-600 rounded-lg px-4 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none w-48\">\n"
                +
                (canManagePlugins
                        ? "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-medium\">\n"
                                +
                                "<input type=\"file\" id=\"plugin-upload\" accept=\".jar\" class=\"hidden\">\n"
                                +
                                "<span class=\"material-symbols-outlined text-[18px]\">add</span>\n" +
                                "<span>Install Mod</span>\n" +
                                "</label>\n"
                        : "")
                +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"w-full overflow-x-auto pb-32 bg-gray-800 rounded-lg shadow ring-1 ring-gray-700\">\n" +
                "<div id=\"plugins-grid\" class=\"p-4 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4\">\n" +
                pluginsHtml.toString() +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "document.getElementById('plugin-search').addEventListener('input', function(e) {\n" +
                "  const search = e.target.value.toLowerCase();\n" +
                "  document.querySelectorAll('#plugins-grid > div').forEach(card => {\n" +
                "    const name = card.textContent.toLowerCase();\n" +
                "    card.style.display = name.includes(search) ? '' : 'none';\n" +
                "  });\n" +
                "});\n" +
                uploadScript +
                "</script>\n";

        return HtmlTemplate.page("Mods", "/plugins", content);
    }

    private static String getModJarFileName(ModContainer mod) {
        try {
            java.util.List<Path> paths = mod.getOrigin().getPaths();
            if (paths.isEmpty()) return null;
            Path p = paths.get(0);
            return p.getFileName().toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
