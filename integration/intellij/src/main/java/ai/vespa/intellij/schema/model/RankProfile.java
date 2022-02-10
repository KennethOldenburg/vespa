// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.intellij.schema.model;

import ai.vespa.intellij.schema.psi.SdFile;
import ai.vespa.intellij.schema.psi.SdRankProfileDefinition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.Optional;

/**
 * A rank profile
 *
 * @author bratseth
 */
public class RankProfile {

    private final SdRankProfileDefinition definition;

    public RankProfile(SdRankProfileDefinition definition) {
        this.definition = definition;
    }

    public SdRankProfileDefinition definition() { return definition; }

    /**
     * Returns the profile of the given name from the given file.
     *
     * @throws IllegalArgumentException if not found
     */
    public static RankProfile fromProjectFile(Project project, String filePath, String profileName) {
        PsiFile[] psiFile = FilenameIndex.getFilesByName(project, filePath, GlobalSearchScope.allScope(project));
        if (psiFile.length == 0)
            throw new IllegalArgumentException(filePath + " could not be opened");
        if (psiFile.length > 1)
            throw new IllegalArgumentException("Multiple files matches " + filePath);
        if ( ! (psiFile[0] instanceof SdFile))
            throw new IllegalArgumentException(filePath + " is not a schema or profile");
        Optional<SdRankProfileDefinition> definition =
                PsiTreeUtil.collectElementsOfType(psiFile[0], SdRankProfileDefinition.class)
                   .stream()
                   .filter(p -> p.getName().equals(profileName))
                   .findAny();
        if (definition.isEmpty())
            throw new IllegalArgumentException("Rank profile '" + profileName + "' is not present in " + filePath);
        return new RankProfile(definition.get());
    }

}
