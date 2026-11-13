package autoepicseven.preview;

import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.lang.documentation.ide.impl.LinksKt;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.lang.documentation.impl.TargetsKt;
import com.intellij.platform.backend.documentation.DocumentationData;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.svg.AdaptiveImageOrigin;
import com.intellij.ui.svg.AdaptiveImageView;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.psi.PyFile;
import com.jetbrains.python.psi.PyReferenceExpression;
import com.jetbrains.python.psi.PyTargetExpression;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs only from the separate test plugin in an isolated, headless IDE process. */
public final class IdeChecks implements ApplicationStarter {
    private static int checks;

    @Override
    public boolean isHeadless() { return true; }

    @Override
    public int getRequiredModality() { return NOT_IN_EDT; }

    @Override
    public void main(List<String> arguments) {
        Project[] project = {null};
        int status = 1;
        try {
            Path root = Path.of(arguments.get(1));
            Path sandbox = Path.of(arguments.get(2));
            Files.createDirectories(sandbox);
            Path probe = sandbox.resolve("preview_probe.py");
            Files.writeString(probe,
                    "from tasks.base.assets.assets_base_resource_bar import SKYSTONE_ICON as PREVIEW_ALIAS\n"
                    + "LOCAL = PREVIEW_ALIAS\nresult = LOCAL\nordinary = 42\n");
            Path otherRoot = sandbox.resolve("OtherAlasProject");
            Path otherWrapper = otherRoot.resolve("tasks/probe/assets/assets_probe.py");
            Path otherImage = otherRoot.resolve("assets/jp/probe/MAIN_GOTO_SECRET_SHOP.png");
            Files.createDirectories(otherRoot.resolve("module/base"));
            Files.createDirectories(otherWrapper.getParent());
            Files.createDirectories(otherImage.getParent());
            Files.writeString(otherRoot.resolve("module/base/button.py"),
                    "raise RuntimeError('Preview must never execute project code')\n");
            Files.copy(root.resolve("assets/cn/base/page/MAIN_GOTO_SECRET_SHOP.png"), otherImage);
            Files.writeString(otherWrapper, "MAIN_GOTO_SECRET_SHOP = ButtonWrapper(\n"
                    + "    name='MAIN_GOTO_SECRET_SHOP',\n"
                    + "    jp=Button(file='./assets/jp/probe/MAIN_GOTO_SECRET_SHOP.png', area=(26, 279, 39, 292)),\n"
                    + "    en=None,\n)\n");
            ApplicationManager.getApplication().invokeAndWait(() -> {
                project[0] = ProjectManager.getInstance().createProject("AssetPreviewChecks", sandbox.toString());
                ApplicationManager.getApplication().runWriteAction(() -> {
                    var module = ModuleManager.getInstance(project[0]).newModule(sandbox.resolve("preview.iml"), "PYTHON_MODULE");
                    ModuleRootModificationUtil.addContentRoot(module, root.toString());
                    ModuleRootModificationUtil.addContentRoot(module, sandbox.toString());
                });
            });
            VirtualFile wrapper = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                    root.resolve("tasks/base/assets/assets_base_page.py"));
            VirtualFile consumer = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                    root.resolve("tasks/secret_shop/secret_shop.py"));
            VirtualFile probeFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(probe);
            VirtualFile otherFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(otherWrapper);
            ApplicationManager.getApplication().runReadAction(() -> inspect(project[0], root, wrapper, consumer, probeFile));
            ApplicationManager.getApplication().runReadAction(() -> inspectOtherRepository(project[0], otherRoot, otherFile));
            ApplicationManager.getApplication().invokeAndWait(() -> inspectImageNavigation(project[0], root));
            System.out.println("PASS: " + checks + " IDE/PSI integration checks");
            status = 0;
        } catch (Throwable failure) {
            failure.printStackTrace();
        }
        // This isolated command owns its process. Exit without saving the temporary
        // project or manually disposing it while Python model coroutines are active.
        System.exit(status);
    }

    private static void inspectOtherRepository(Project project, Path root, VirtualFile wrapper) {
        PyFile file = (PyFile) PsiManager.getInstance(project).findFile(wrapper);
        PyTargetExpression asset = file.findTopLevelAttribute("MAIN_GOTO_SECRET_SHOP");
        AssetPsi.Definition definition = AssetPsi.definition(asset);
        check(definition != null && definition.root().equals(root),
                "An ALAS-style repository with a duplicate asset name locates its own root");
        var target = TargetsKt.documentationTargets(file, asset.getTextOffset() + 3).getFirst();
        var data = (DocumentationData) target.computeDocumentation();
        check(data.getHtml().contains("<b>assets/jp/probe/MAIN_GOTO_SECRET_SHOP.png</b>")
                        && data.getHtml().contains("<b>en</b><br>此版本没有素材"),
                "Other ALAS projects display source directories and unknown variants without server translations");
        inspectNativeImageAddresses(data.getHtml());
        VirtualFile image = AssetDocumentationProvider.imageFile(asset, PreviewImages.OPEN_PREFIX + "0");
        check(image != null && Path.of(image.getPath()).equals(root.resolve("assets/jp/probe/MAIN_GOTO_SECRET_SHOP.png")),
                "The other repository's image action resolves its own source despite a duplicate asset name");
    }

    private static void inspect(Project project, Path root, VirtualFile wrapper, VirtualFile consumer, VirtualFile probe) {
        PyFile file = (PyFile) PsiManager.getInstance(project).findFile(wrapper);
        PyTargetExpression target = file.findTopLevelAttribute("MAIN_GOTO_SECRET_SHOP");
        AssetPsi.Definition definition = AssetPsi.definition(target);
        check(definition != null && definition.root().equals(root), "Declaration locates its own repository");
        check(definition.frames().size() == 5, "PSI preserves both servers' frames and the unsupported version: " + definition.frames());
        check(definition.frames().get(1).area().top() == 357, "PSI reads each frame's own crop");

        DocumentationProvider provider = DocumentationManager.getProviderFromElement(target);
        String html = provider.generateDoc(target, null);
        check(html != null && html.contains(PreviewImages.IMAGE_PREFIX), "Registered provider wins for asset documentation");
        inspectNativeImageAddresses(html);
        String hover = provider.generateHoverDoc(target, null);
        check(hover != null && hover.contains(PreviewImages.IMAGE_PREFIX), "Mouse hover uses asset documentation");
        Matcher image = Pattern.compile("src='([^']+)'").matcher(html);
        check(image.find() && DocumentationManager.getElementImage(target, image.group(1)) != null,
                "Native documentation image resolver returns the cropped image");

        PyFile task = (PyFile) PsiManager.getInstance(project).findFile(consumer);
        PyReferenceExpression reference = PsiTreeUtil.findChildrenOfType(task, PyReferenceExpression.class).stream()
                .filter(item -> "SKYSTONE_ICON".equals(item.getName()))
                .filter(item -> !(item.getParent() instanceof com.jetbrains.python.psi.PyImportElement))
                .findFirst().orElseThrow();
        PyTargetExpression resolved = AssetPsi.declaration(reference);
        check(resolved != null && "SKYSTONE_ICON".equals(resolved.getName()), "Real task import resolves to its asset");
        check(AssetPsi.definition(reference) != null, "Hover works at the imported usage site");
        var offsetTargets = TargetsKt.documentationTargets(task, reference.getTextOffset() + 3);
        check(!offsetTargets.isEmpty(), "The editor finds documentation at the asset's source offset");
        var offsetResult = offsetTargets.getFirst().computeDocumentation();
        check(offsetResult instanceof DocumentationData data && data.getHtml().contains(PreviewImages.IMAGE_PREFIX),
                "The actual file-offset documentation entry returns asset images: " + offsetResult);

        VirtualFile activityFile = LocalFileSystem.getInstance().findFileByNioFile(
                root.resolve("tasks/activity/free_gacha_20.py"));
        PyFile activity = (PyFile) PsiManager.getInstance(project).findFile(activityFile);
        PyReferenceExpression selected = PsiTreeUtil.findChildrenOfType(activity, PyReferenceExpression.class).stream()
                .filter(item -> "FREE_20_GACHA_SELECTED".equals(item.getName()))
                .filter(item -> !(item.getParent() instanceof com.jetbrains.python.psi.PyImportElement))
                .findFirst().orElseThrow();
        check(AssetPsi.definition(selected) != null, "The reported activity asset resolves at its usage site");
        var activityTargets = TargetsKt.documentationTargets(activity, selected.getTextOffset() + 3);
        check(!activityTargets.isEmpty(), "The reported activity has a documentation target");
        var activityResult = activityTargets.getFirst().computeDocumentation();
        check(activityResult instanceof DocumentationData data && data.getHtml().contains(PreviewImages.IMAGE_PREFIX),
                "The reported activity's full documentation contains images: " + activityResult);
        String activityHint = activityTargets.getFirst().computeDocumentationHint();
        check("FREE_20_GACHA_SELECTED".equals(activityHint),
                "The activity hover hint preserves the asset identity: " + activityHint);
        DocumentationData activityData = (DocumentationData) activityResult;
        Matcher activityImages = Pattern.compile("src='([^']+)'").matcher(activityData.getHtml());
        int[] expectedWidths = {104, 24};
        int activityImageCount = 0;
        while (activityImages.find()) {
            var resolver = activityData.getContent$intellij_platform_lang_impl().getImageResolver();
            var resolvedImage = resolver == null ? null : resolver.resolveImage(activityImages.group(1));
            check(activityImageCount < expectedWidths.length && resolvedImage != null
                            && resolvedImage.getWidth(null) == expectedWidths[activityImageCount],
                    "The reported activity's documentation result directly resolves each cropped image");
            activityImageCount++;
        }
        check(activityImageCount == 2 && activityData.getHtml().contains("此版本没有素材"),
                "The reported activity displays both available servers and the unsupported version");
        var restored = activityTargets.getFirst().createPointer().dereference();
        check(restored != null && restored.computeDocumentation() instanceof DocumentationData data
                        && data.getHtml().contains(PreviewImages.IMAGE_PREFIX),
                "The documentation popup can restore its target outside the original request");

        PyFile aliases = (PyFile) PsiManager.getInstance(project).findFile(probe);
        PyTargetExpression alias = AssetPsi.declaration(aliases.findTopLevelAttribute("LOCAL"));
        check(alias != null && "SKYSTONE_ICON".equals(alias.getName()),
                "Import and assignment aliases resolve: " + alias);
        check(AssetPsi.declaration(aliases.findTopLevelAttribute("ordinary")) == null,
                "Ordinary Python variables retain the default documentation");
        var ordinaryTargets = TargetsKt.documentationTargets(aliases,
                aliases.findTopLevelAttribute("ordinary").getTextOffset() + 2);
        check(!ordinaryTargets.isEmpty()
                        && !ordinaryTargets.getFirst().getClass().getName().startsWith("autoepicseven.preview."),
                "The new source-offset provider leaves ordinary variables to Python documentation");

        PyFile unavailable = (PyFile) PsiFileFactory.getInstance(project).createFileFromText("preview_generated.py",
                PythonFileType.INSTANCE, "TEST = ButtonWrapper(name='TEST', cn=None, global_cn=Button("
                        + "file='./assets/example.png', area=(1, 2, 3, 4), search=(0, 0, 5, 5)))\n");
        AssetPsi.Definition variants = AssetPsi.parse(unavailable.findTopLevelAttribute("TEST"), root);
        check(variants.frames().size() == 2 && variants.frames().get(0).problem() != null,
                "Unsupported namespaces are preserved without importing Python");
        check(variants.frames().get(1).area().width() == 2, "Recognition crop uses area rather than search");
    }

    private static void inspectImageNavigation(Project project, Path root) {
        VirtualFile wrapper = LocalFileSystem.getInstance().findFileByNioFile(
                root.resolve("tasks/activity/assets/assets_activity_special_26_8_27.py"));
        PyFile assets = (PyFile) PsiManager.getInstance(project).findFile(wrapper);
        PyTargetExpression obtained = assets.findTopLevelAttribute("FREE_20_GACHA_OBTAINED");
        var target = TargetsKt.documentationTargets(assets, obtained.getTextOffset() + 3).getFirst();
        var result = (DocumentationData) target.computeDocumentation();
        inspectNativeImageAddresses(result.getHtml());
        String link = PreviewImages.OPEN_PREFIX + "0";
        VirtualFile expected = AssetDocumentationProvider.imageFile(obtained, link);
        check(expected != null && expected.getPath().endsWith(
                        "/assets/share/activity/special/26_8_27/FREE_20_GACHA_OBTAINED.png"),
                "The reported reward asset link resolves to its shared source PNG");
        // The headless TestEditorManager defaults to text documents, including
        // binary files. Use its supported provider override with the bundled
        // native image editor, so this still exercises a real PNG editor.
        var imageProvider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList().stream()
                .filter(item -> item.getClass().getName().equals("org.intellij.images.editor.impl.ImageFileEditorProvider"))
                .findFirst().orElseThrow();
        check(imageProvider.accept(project, expected), "The bundled image editor accepts the source PNG");
        expected.putUserData(FileEditorProvider.KEY, imageProvider);
        check(LinksKt.openUrl(project, target.createPointer(), link),
                "The native documentation link route consumes the open-image action");
        // TestEditorManager.isFileOpen only checks its text-editor map. Binary
        // editors are tracked by the tab splitter, exposed through getEditors.
        var openedEditors = FileEditorManager.getInstance(project).getEditors(expected);
        check(java.util.Arrays.stream(openedEditors).anyMatch(editor -> expected.equals(editor.getFile())),
                "Activating the documentation action opens the exact source file inside the IDE");
        check(java.util.Arrays.stream(openedEditors)
                        .anyMatch(editor -> editor.getClass().getName().startsWith("org.intellij.images.editor.")),
                "The opened source uses the real image editor rather than a text editor");
        check(AssetDocumentationProvider.imageFile(obtained, PreviewImages.OPEN_PREFIX + "999") == null
                        && AssetDocumentationProvider.imageFile(obtained,
                        PreviewImages.OPEN_PREFIX + "../../module/base/button.py") == null,
                "Image actions reject unavailable frames and path payloads");

        PyTargetExpression selected = assets.findTopLevelAttribute("FREE_20_GACHA_SELECTED");
        VirtualFile cn = AssetDocumentationProvider.imageFile(selected, PreviewImages.OPEN_PREFIX + "0");
        VirtualFile global = AssetDocumentationProvider.imageFile(selected, PreviewImages.OPEN_PREFIX + "1");
        check(cn != null && global != null && !cn.equals(global)
                        && cn.getPath().contains("/assets/cn/") && global.getPath().contains("/assets/global_cn/"),
                "Different server image actions open their own source files");
        VirtualFile pageFile = LocalFileSystem.getInstance().findFileByNioFile(
                root.resolve("tasks/base/assets/assets_base_page.py"));
        PyTargetExpression page = ((PyFile) PsiManager.getInstance(project).findFile(pageFile))
                .findTopLevelAttribute("MAIN_GOTO_SECRET_SHOP");
        VirtualFile second = AssetDocumentationProvider.imageFile(page, PreviewImages.OPEN_PREFIX + "1");
        check(second != null && second.getName().equals("MAIN_GOTO_SECRET_SHOP.2.png"),
                "Multi-frame image actions preserve the selected frame's source");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void inspectNativeImageAddresses(String html) {
        try {
            var kit = new HTMLEditorKit();
            var document = kit.createDefaultDocument();
            kit.read(new StringReader(html), document, 0);
            var elements = new javax.swing.text.ElementIterator(document);
            var originMethod = AdaptiveImageView.class.getDeclaredMethod("getImageOrigin");
            originMethod.setAccessible(true);
            int images = 0;
            for (var element = elements.first(); element != null; element = elements.next()) {
                if (element.getAttributes().getAttribute(javax.swing.text.StyleConstants.NameAttribute) == HTML.Tag.IMG) {
                    Object origin = originMethod.invoke(new AdaptiveImageView(element));
                    check(origin instanceof AdaptiveImageOrigin.DataUrl,
                            "The native editor image view accepts the embedded PNG before requesting a renderer");
                    images++;
                }
            }
            check(images > 0, "Native image parsing inspects actual documentation images");
        } catch (ReflectiveOperationException | java.io.IOException | javax.swing.text.BadLocationException failure) {
            throw new AssertionError("Native image parsing failed", failure);
        }
    }
}
