package autoepicseven.preview;

import com.intellij.lang.documentation.psi.PsiElementDocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.psi.PyFile;
import com.jetbrains.python.psi.PyTargetExpression;

import java.util.List;

/** Resolve the source identifier before Python's generic documentation selects a type. */
public final class AssetDocumentationTargetProvider implements DocumentationTargetProvider {
    @Override
    public List<? extends DocumentationTarget> documentationTargets(PsiFile file, int offset) {
        if (!(file instanceof PyFile) || offset < 0 || offset >= file.getTextLength()) return List.of();
        PyTargetExpression declaration = AssetPsi.declaration(file.findElementAt(offset));
        if (declaration == null || AssetPsi.definition(declaration) == null) return List.of();
        // Keep the source-offset entry, and use the native PSI target so link
        // activation reaches ExternalDocumentationHandler on the UI thread.
        return List.of(new PsiElementDocumentationTarget(file.getProject(), declaration, file.findElementAt(offset)));
    }
}
