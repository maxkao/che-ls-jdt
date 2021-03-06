/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.jdt.ls.extension.core.internal.externallibrary;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static org.eclipse.che.jdt.ls.extension.core.internal.Utils.ensureNotCancelled;
import static org.eclipse.jdt.ls.core.internal.JDTUtils.PERIOD;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.che.jdt.ls.extension.api.dto.Jar;
import org.eclipse.che.jdt.ls.extension.api.dto.JarEntry;
import org.eclipse.che.jdt.ls.extension.core.internal.JavaModelUtil;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJarEntryResource;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JarEntryDirectory;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.hover.JavaElementLabels;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;

/**
 * Utilities for working with External libraries.
 *
 * @author Valeriy Svydenko
 */
public class LibraryNavigation {
  public static final String PACKAGE_ENTRY_TYPE = "PACKAGE";
  public static final String FOLDER_ENTRY_TYPE = "FOLDER";
  public static final String CLASS_FILE_ENTRY_TYPE = "CLASS_FILE";
  public static final String FILE_ENTRY_TYPE = "FILE";

  private static final Comparator<JarEntry> COMPARATOR =
      (o1, o2) -> {
        if (PACKAGE_ENTRY_TYPE.equals(o1.getEntryType())
            && !PACKAGE_ENTRY_TYPE.equals(o2.getEntryType())) {
          return 1;
        }

        if (PACKAGE_ENTRY_TYPE.equals(o2.getEntryType())
            && !PACKAGE_ENTRY_TYPE.equals(o1.getEntryType())) {
          return 1;
        }

        if (CLASS_FILE_ENTRY_TYPE.equals(o1.getEntryType())
            && !CLASS_FILE_ENTRY_TYPE.equals(o2.getEntryType())) {
          return 1;
        }

        if (!CLASS_FILE_ENTRY_TYPE.equals(o1.getEntryType())
            && CLASS_FILE_ENTRY_TYPE.equals(o2.getEntryType())) {
          return 1;
        }

        if (FOLDER_ENTRY_TYPE.equals(o1.getEntryType())
            && !FOLDER_ENTRY_TYPE.equals(o2.getEntryType())) {
          return 1;
        }

        if (!FOLDER_ENTRY_TYPE.equals(o1.getEntryType())
            && FOLDER_ENTRY_TYPE.equals(o2.getEntryType())) {
          return 1;
        }

        if (FILE_ENTRY_TYPE.equals(o1.getEntryType())
            && !FILE_ENTRY_TYPE.equals(o2.getEntryType())) {
          return -1;
        }

        if (!FILE_ENTRY_TYPE.equals(o1.getEntryType())
            && FILE_ENTRY_TYPE.equals(o2.getEntryType())) {
          return -1;
        }

        if (o1.getEntryType().equals(o2.getEntryType())) {
          return o1.getName().compareTo(o2.getName());
        }

        return 0;
      };

  /**
   * Computes dependencies of the project.
   *
   * @param projectUri project URI
   * @param pm a progress monitor
   * @return list of jars {@link Jar}
   * @throws JavaModelException if the element does not exist or if an exception occurs while
   *     accessing its corresponding resource
   */
  public static List<Jar> getProjectDependencyJars(String projectUri, IProgressMonitor pm)
      throws JavaModelException {
    IJavaProject javaProject = JavaModelUtil.getJavaProject(projectUri);
    if (javaProject == null) {
      throw new IllegalArgumentException(format("Project for '%s' not found", projectUri));
    }

    IMavenProjectRegistry mavenProjectRegistry = MavenPlugin.getMavenProjectRegistry();
    IMavenProjectFacade mavenProject = mavenProjectRegistry.getProject(javaProject.getProject());
    if (mavenProject != null && "pom".equals(mavenProject.getPackaging())) {
      return getDefaultJars(javaProject, pm);
    }

    return toJars(javaProject.getPackageFragmentRoots(), pm);
  }

  private static List<Jar> getDefaultJars(IJavaProject javaProject, IProgressMonitor pm)
      throws JavaModelException {

    IClasspathEntry[] classpathEntries =
        JavaCore.getClasspathContainer(
                JavaRuntime.getDefaultJREContainerEntry().getPath(), javaProject)
            .getClasspathEntries();

    List<IPackageFragmentRoot> fragmentRoots =
        Stream.of(classpathEntries)
            .map(
                classpathEntry ->
                    javaProject.getPackageFragmentRoot(classpathEntry.getPath().toOSString()))
            .collect(Collectors.toList());

    return toJars(fragmentRoots.toArray(new IPackageFragmentRoot[fragmentRoots.size()]), pm);
  }

  private static List<Jar> toJars(IPackageFragmentRoot[] fragmentRoots, IProgressMonitor pm)
      throws JavaModelException {

    List<Jar> jars = new ArrayList<>();
    for (IPackageFragmentRoot fragmentRoot : fragmentRoots) {
      ensureNotCancelled(pm);
      if (fragmentRoot instanceof JarPackageFragmentRoot) {
        Jar jar = new Jar(fragmentRoot.getElementName(), fragmentRoot.getHandleIdentifier());
        jars.add(jar);
      }
    }
    return jars;
  }

  /**
   * Returns packages and files inside a given fragment or root.
   *
   * @param id id of library's node
   * @param pm a progress monitor
   * @return list of entries
   * @throws JavaModelException if an exception occurs while accessing its corresponding resource
   */
  public static List<JarEntry> getPackageFragmentRootContent(String id, IProgressMonitor pm)
      throws JavaModelException {
    IPackageFragmentRoot root = (IPackageFragmentRoot) JavaCore.create(id);

    if (root == null) {
      return emptyList();
    }

    Object[] rootContent = getPackageFragmentRootContent(root, pm);

    return convertToJarEntry(rootContent);
  }

  /**
   * Computes children of library.
   *
   * @param rootId id of root node
   * @param pm a progress monitor
   * @param path path of the parent node
   * @return list of entries {@link JarEntry}
   * @throws JavaModelException if an exception occurs while accessing its corresponding resource
   */
  public static List<JarEntry> getChildren(String rootId, String path, IProgressMonitor pm)
      throws JavaModelException {
    IPackageFragmentRoot root = (IPackageFragmentRoot) JavaCore.create(rootId);
    if (root == null) {
      return emptyList();
    }

    if (path.startsWith("/")) {
      // jar file and folders
      Object[] resources = root.getNonJavaResources();
      for (Object resource : resources) {
        ensureNotCancelled(pm);

        if (resource instanceof JarEntryDirectory) {
          JarEntryDirectory directory = (JarEntryDirectory) resource;
          Object[] children = findJarDirectoryChildren(directory, path);
          if (children != null) {
            return convertToJarEntry(children);
          }
        }
      }

    } else {
      // packages and class files
      IPackageFragment fragment = root.getPackageFragment(path);
      if (fragment == null) {
        return emptyList();
      }
      return convertToJarEntry(getPackageContent(fragment, pm));
    }
    return emptyList();
  }

  private static Object[] getPackageContent(IPackageFragment fragment, IProgressMonitor pm)
      throws JavaModelException {
    // hierarchical package mode
    ArrayList<Object> result = new ArrayList<>();

    collectPackageChildren((IPackageFragmentRoot) fragment.getParent(), fragment, result, pm);
    IClassFile[] classFiles = fragment.getClassFiles();
    List<IClassFile> filtered = new ArrayList<>();
    // filter inner classes
    for (IClassFile classFile : classFiles) {
      if (!classFile.getElementName().contains("$")) {
        filtered.add(classFile);
      }
    }
    Object[] nonPackages = concatenate(filtered.toArray(), fragment.getNonJavaResources());
    if (result.isEmpty()) {
      return nonPackages;
    }
    Collections.addAll(result, nonPackages);
    return result.toArray();
  }

  private static Object[] findJarDirectoryChildren(JarEntryDirectory directory, String path) {
    String directoryPath = directory.getFullPath().toOSString();
    if (directoryPath.equals(path)) {
      return directory.getChildren();
    }
    if (path.startsWith(directoryPath)) {
      for (IJarEntryResource resource : directory.getChildren()) {
        String childrenPath = resource.getFullPath().toOSString();
        if (childrenPath.equals(path)) {
          return resource.getChildren();
        }
        if (path.startsWith(childrenPath) && resource instanceof JarEntryDirectory) {
          findJarDirectoryChildren((JarEntryDirectory) resource, path);
        }
      }
    }
    return null;
  }

  private static Object[] getPackageFragmentRootContent(
      IPackageFragmentRoot root, IProgressMonitor pm) throws JavaModelException {
    ArrayList<Object> result = new ArrayList<>();
    collectPackageChildren(root, null, result, pm);
    Object[] nonJavaResources = root.getNonJavaResources();
    Collections.addAll(result, nonJavaResources);
    return result.toArray();
  }

  /**
   * Returns packages, folders and files inside a given fragment or root.
   *
   * @param parent the parent package fragment root
   * @param fragment the package to get the children for or 'null' to get the children of the root
   * @param result Collection where the resulting elements are added
   * @throws JavaModelException if fetching the children fails
   */
  private static void collectPackageChildren(
      IPackageFragmentRoot parent,
      IPackageFragment fragment,
      Collection<Object> result,
      IProgressMonitor pm)
      throws JavaModelException {
    IJavaElement[] children = parent.getChildren();
    String prefix = fragment != null ? fragment.getElementName() + PERIOD : ""; // $NON-NLS-1$
    int prefixLen = prefix.length();
    for (IJavaElement child : children) {
      ensureNotCancelled(pm);
      IPackageFragment curr = (IPackageFragment) child;
      String name = curr.getElementName();
      if (name.startsWith(prefix)
          && name.length() > prefixLen
          && name.indexOf(PERIOD, prefixLen) == -1) {
        curr = getFolded(children, curr);
        result.add(curr);
      } else if (fragment == null && curr.isDefaultPackage()) {
        IJavaElement[] currChildren = curr.getChildren();
        if (currChildren != null && currChildren.length >= 1) {
          result.add(curr);
        }
      }
    }
  }

  private static IPackageFragment getFolded(IJavaElement[] children, IPackageFragment pack)
      throws JavaModelException {
    while (isEmpty(pack)) {
      IPackageFragment collapsed = findSinglePackageChild(pack, children);
      if (collapsed == null) {
        return pack;
      }
      pack = collapsed;
    }
    return pack;
  }

  private static IPackageFragment findSinglePackageChild(
      IPackageFragment fragment, IJavaElement[] children) {
    String prefix = fragment.getElementName() + PERIOD;
    int prefixLen = prefix.length();
    IPackageFragment found = null;
    for (IJavaElement element : children) {
      String name = element.getElementName();
      if (name.startsWith(prefix)
          && name.length() > prefixLen
          && name.indexOf(PERIOD, prefixLen) == -1) {
        if (found == null) {
          found = (IPackageFragment) element;
        } else {
          return null;
        }
      }
    }
    return found;
  }

  private static boolean isEmpty(IPackageFragment fragment) throws JavaModelException {
    return !fragment.containsJavaResources() && fragment.getNonJavaResources().length == 0;
  }

  private static List<JarEntry> convertToJarEntry(Object[] rootContent) throws JavaModelException {
    List<JarEntry> result = new ArrayList<>();
    for (Object root : rootContent) {
      if (root instanceof IPackageFragment) {
        IPackageFragment packageFragment = (IPackageFragment) root;
        JarEntry entry =
            new JarEntry(
                getSpecificText((IJavaElement) root),
                packageFragment.getElementName(),
                PACKAGE_ENTRY_TYPE,
                null);
        result.add(entry);
      }

      if (root instanceof IClassFile) {
        IClassFile classFile = (IClassFile) root;
        JarEntry entry =
            new JarEntry(
                classFile.getElementName(),
                classFile.findPrimaryType().getFullyQualifiedName(),
                CLASS_FILE_ENTRY_TYPE,
                JDTUtils.toUri(classFile));
        result.add(entry);
      }

      if (root instanceof IJarEntryResource) {
        result.add(getJarEntryResource((IJarEntryResource) root));
      }
    }
    result.sort(COMPARATOR);
    return result;
  }

  private static JarEntry getJarEntryResource(IJarEntryResource resource) {
    String path = resource.getName();
    Object parent = resource.getParent();

    while (parent instanceof IJarEntryResource) {
      IJarEntryResource p = (IJarEntryResource) parent;
      path = p.getName() + "/" + path;
      parent = p.getParent();
    }

    JarEntry entry = new JarEntry();
    if (resource.isFile()) {
      entry.setEntryType(FILE_ENTRY_TYPE);
    } else {
      entry.setEntryType(FOLDER_ENTRY_TYPE);
    }

    entry.setName(resource.getName());
    entry.setPath(resource.getFullPath().toOSString());
    try {
      entry.setUri(
          "chelib://"
              + URLEncoder.encode(((IJavaElement) parent).getHandleIdentifier(), "UTF-8")
              + "/"
              + path);
    } catch (UnsupportedEncodingException e) {
      // utf-8 is mandatory
      throw new RuntimeException("Should not happen", e);
    }
    return entry;
  }

  private static String getSpecificText(IJavaElement element) {
    if (element instanceof IPackageFragment) {
      IPackageFragment fragment = (IPackageFragment) element;
      IJavaElement parent = getHierarchicalPackageParent(fragment);
      if (parent instanceof IPackageFragment) {
        return getNameDelta((IPackageFragment) parent, fragment);
      }
    }

    return JavaElementLabels.getElementLabel(element, 0);
  }

  private static String getNameDelta(IPackageFragment parent, IPackageFragment fragment) {
    String prefix = parent.getElementName() + PERIOD;
    String fullName = fragment.getElementName();
    if (fullName.startsWith(prefix)) {
      return fullName.substring(prefix.length());
    }
    return fullName;
  }

  private static IJavaElement getHierarchicalPackageParent(IPackageFragment child) {
    String name = child.getElementName();
    IPackageFragmentRoot parent = (IPackageFragmentRoot) child.getParent();
    int index = name.lastIndexOf(PERIOD);
    if (index != -1) {
      String realParentName = name.substring(0, index);
      IPackageFragment element = parent.getPackageFragment(realParentName);
      if (element.exists()) {
        try {
          if (isEmpty(element) && findSinglePackageChild(element, parent.getChildren()) != null) {
            return getHierarchicalPackageParent(element);
          }
        } catch (JavaModelException e) {
          // ignore
        }
        return element;
      }
    }
    return parent;
  }

  /**
   * Utility method to concatenate two arrays.
   *
   * @param a1 the first array
   * @param a2 the second array
   * @return the concatenated array
   */
  private static Object[] concatenate(Object[] a1, Object[] a2) {
    int a1Len = a1.length;
    int a2Len = a2.length;
    if (a1Len == 0) {
      return a2;
    }
    if (a2Len == 0) {
      return a1;
    }
    Object[] res = new Object[a1Len + a2Len];
    System.arraycopy(a1, 0, res, 0, a1Len);
    System.arraycopy(a2, 0, res, a1Len, a2Len);
    return res;
  }
}
