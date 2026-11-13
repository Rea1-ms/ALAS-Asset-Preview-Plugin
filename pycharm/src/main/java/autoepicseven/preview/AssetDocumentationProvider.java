package autoepicseven.preview;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.ExternalDocumentationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;

import java.awt.Image;
import java.io.IOException;

public final class AssetDocumentationProvider extends AbstractDocumentationProvider implements ExternalDocumentationHandler {
    private final PreviewImages previews = new PreviewImages();

    @Override
    public String generateDoc(PsiElement element, PsiElement originalElement) {
        AssetPsi.Definition definition = AssetPsi.definition(element);
        if (definition == null) definition = AssetPsi.definition(originalElement);
        return definition == null ? null : previews.render(definition);
    }

    @Override
    public String generateHoverDoc(PsiElement element, PsiElement originalElement) {
        return generateDoc(element, originalElement);
    }

    @Override
    public String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        var target = AssetPsi.declaration(element);
        return target == null ? null : PreviewImages.escape(target.getName());
    }

    public static VirtualFile imageFile(PsiElement context, String url) {
        if (url == null || !url.startsWith(PreviewImages.OPEN_PREFIX)) return null;
        String frameIndex = url.substring(PreviewImages.OPEN_PREFIX.length());
        if (!frameIndex.matches("[0-9]{1,3}")) return null;
        AssetPsi.Definition definition = AssetPsi.definition(context);
        if (definition == null) return null;
        int index = Integer.parseInt(frameIndex);
        if (index >= definition.frames().size()) return null;
        try {
            return LocalFileSystem.getInstance().findFileByNioFile(
                    PreviewImages.source(definition.root(), definition.frames().get(index)));
        } catch (IOException unavailable) {
            return null;
        }
    }

    @Override
    public boolean handleExternalLink(PsiManager manager, String url, PsiElement context) {
        if (url == null || !url.startsWith(PreviewImages.OPEN_PREFIX)) return false;
        VirtualFile image = imageFile(context, url);
        if (image != null) FileEditorManager.getInstance(manager.getProject()).openFile(image, true);
        // Consume our links even if the source was moved while the popup was
        // open; never pass an unresolved image command to an external browser.
        return true;
    }

    @Override
    public PsiElement getCustomDocumentationElement(Editor editor, PsiFile file, PsiElement context, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null) return null;
        return AssetPsi.definition(leaf) == null ? null : AssetPsi.declaration(leaf);
    }

    @Override
    public Image getLocalImageForElement(PsiElement element, String url) {
        return previews.image(url);
    }
}
