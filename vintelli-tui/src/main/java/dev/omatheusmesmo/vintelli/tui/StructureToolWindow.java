package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
public final class StructureToolWindow implements ToolWindow {

    private final List<NavigationCapability> providers;
    private final BuiltinEditor editor;
    private final Provider<ShellThemes> themes;

    private final List<NavigationCapability.DocumentSymbol> roots = new ArrayList<>();
    private String activeFile = "";
    private String unavailableReason = null;
    private int selectedIndex = 0;
    private Consumer<NavigationCapability.DocumentSymbol> navigateHandler;

    @Inject
    public StructureToolWindow(jakarta.enterprise.inject.Instance<NavigationCapability> providers,
            BuiltinEditor editor, Provider<ShellThemes> themes) {
        this(toList(providers), editor, themes);
    }

    public StructureToolWindow(Provider<ShellThemes> themes) {
        this(List.of(), null, themes);
    }

    public StructureToolWindow(ShellThemes themes) {
        this(List.of(), null, () -> themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, BuiltinEditor editor, ShellThemes themes) {
        this(providers, editor, () -> themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, ShellThemes themes) {
        this(providers, null, () -> themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, Provider<ShellThemes> themes) {
        this(providers, null, themes);
    }

    public StructureToolWindow(NavigationCapability provider, BuiltinEditor editor, Provider<ShellThemes> themes) {
        this(provider == null ? List.of() : List.of(provider), editor, themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, BuiltinEditor editor, Provider<ShellThemes> themes) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.editor = editor;
        this.themes = themes;
    }

    private static List<NavigationCapability> toList(jakarta.enterprise.inject.Instance<NavigationCapability> instance) {
        List<NavigationCapability> result = new ArrayList<>();
        if (instance != null) {
            for (NavigationCapability provider : instance) {
                result.add(provider);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String id() {
        return "structure";
    }

    @Override
    public String title() {
        return "Structure";
    }

    @Override
    public char shortcut() {
        return '6';
    }

    @Override
    public int ordinal() {
        return 4;
    }

    @Override
    public Anchor anchor() {
        return Anchor.RIGHT;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        List<NavigationCapability.DocumentSymbol> flattened = flattened();
        if (flattened.isEmpty()) {
            return false;
        }
        if (event.isKey(KeyCode.UP)) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return true;
        }
        if (event.isKey(KeyCode.DOWN)) {
            selectedIndex = Math.min(flattened.size() - 1, selectedIndex + 1);
            return true;
        }
        if (event.isKey(KeyCode.ENTER)) {
            if (navigateHandler != null && selectedIndex >= 0 && selectedIndex < flattened.size()) {
                navigateHandler.accept(flattened.get(selectedIndex));
            }
            return true;
        }
        return false;
    }

    @Override
    public void render(Frame frame, Rect area) {
        refreshIfNeeded();
        ShellThemes activeThemes = themes == null ? null : themes.get();
        Style contentStyle = activeThemes == null ? Style.EMPTY
                : activeThemes.resolver("tool-window").toStyle();
        Title blockTitle = Title.from(Span.styled(title(),
                activeThemes == null ? Style.EMPTY : activeThemes.resolver("tool-window-title").toStyle()));
        Block.Builder blockBuilder = Block.builder()
                .title(blockTitle)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .style(contentStyle);
        if (activeThemes != null) {
            activeThemes.resolver("tool-window").borderColor().ifPresent(blockBuilder::borderColor);
        }
        List<String> lines = new ArrayList<>();
        if (unavailableReason != null) {
            lines.add(unavailableReason);
        } else if (roots.isEmpty()) {
            lines.add("No symbols");
        } else {
            appendSymbols(lines, roots, 0);
        }
        Paragraph paragraph = Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(contentStyle)
                .block(blockBuilder.build())
                .build();
        frame.renderWidget(paragraph, area);
    }

    public List<NavigationCapability.DocumentSymbol> symbols() {
        return List.copyOf(roots);
    }

    public void setSymbols(List<NavigationCapability.DocumentSymbol> symbols) {
        roots.clear();
        if (symbols != null) {
            roots.addAll(symbols);
        }
        unavailableReason = null;
        selectedIndex = 0;
    }

    public void setUnavailable(String reason) {
        roots.clear();
        unavailableReason = reason == null ? "LSP unavailable" : reason;
    }

    public void setActiveFile(Path file) {
        activeFile = file == null ? "" : file.toString();
        fetch();
    }

    public void setActiveFile(String file) {
        activeFile = file == null ? "" : file;
        fetch();
    }

    public void refresh() {
        String current = currentFile();
        if (!current.equals(activeFile)) {
            activeFile = current;
        }
        fetch();
    }

    public void setNavigateHandler(Consumer<NavigationCapability.DocumentSymbol> handler) {
        this.navigateHandler = handler;
    }

    public NavigationCapability.DocumentSymbol selectedSymbol() {
        List<NavigationCapability.DocumentSymbol> flattened = flattened();
        if (flattened.isEmpty() || selectedIndex < 0 || selectedIndex >= flattened.size()) {
            return null;
        }
        return flattened.get(selectedIndex);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public String status() {
        if (unavailableReason != null) {
            return unavailableReason;
        }
        return roots.size() + " symbols";
    }

    private String currentFile() {
        if (editor != null) {
            return editor.activeDocument().map(BuiltinEditor.DocumentView::file).orElse("");
        }
        return activeFile;
    }

    private void refreshIfNeeded() {
        String current = currentFile();
        if (!current.equals(activeFile)) {
            activeFile = current;
            fetch();
        }
    }

    private void fetch() {
        roots.clear();
        unavailableReason = null;
        if (activeFile.isBlank()) {
            unavailableReason = "No active file";
            return;
        }
        NavigationCapability provider = availableProvider();
        if (provider == null) {
            unavailableReason = "LSP unavailable";
            return;
        }
        try {
            CapabilityResult<List<NavigationCapability.DocumentSymbol>> result = provider.documentSymbols(Path.of(activeFile));
            if (result instanceof CapabilityResult.Ok<List<NavigationCapability.DocumentSymbol>> ok) {
                roots.addAll(ok.value());
                selectedIndex = 0;
            } else {
                unavailableReason = result.failureOf()
                        .map(failure -> failure.reason() + ": " + failure.detail())
                        .orElse("LSP unavailable");
            }
        } catch (RuntimeException failure) {
            unavailableReason = "LSP unavailable: " + failure.getMessage();
        }
    }

    private NavigationCapability availableProvider() {
        for (NavigationCapability provider : providers) {
            if (provider != null && provider.health() != null
                    && provider.health().state() == ProviderHealth.State.AVAILABLE) {
                return provider;
            }
        }
        return null;
    }

    private List<NavigationCapability.DocumentSymbol> flattened() {
        List<NavigationCapability.DocumentSymbol> result = new ArrayList<>();
        for (NavigationCapability.DocumentSymbol symbol : roots) {
            flatten(symbol, result);
        }
        return result;
    }

    private static void flatten(NavigationCapability.DocumentSymbol symbol,
            List<NavigationCapability.DocumentSymbol> result) {
        result.add(symbol);
        for (NavigationCapability.DocumentSymbol child : symbol.children()) {
            flatten(child, result);
        }
    }

    private static void appendSymbols(List<String> lines, List<NavigationCapability.DocumentSymbol> symbols, int depth) {
        String indent = "  ".repeat(Math.max(0, depth));
        for (NavigationCapability.DocumentSymbol symbol : symbols) {
            lines.add(indent + symbol.name());
            if (symbol.children() != null && !symbol.children().isEmpty()) {
                appendSymbols(lines, symbol.children(), depth + 1);
            }
        }
    }
}
