/*******************************************************************************
 * Copyright (c) 2018, 2023 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Mickael Istria (Red Hat Inc.) - initial implementation
 *   Pierre-Yves B. - Issue #180 Wrong path to nodeDebug.js
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.debug.node;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.lsp4e.debug.DSPPlugin;
import org.eclipse.lsp4e.debug.launcher.DSPLaunchDelegate;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.wildwebdeveloper.Activator;
import org.eclipse.wildwebdeveloper.debug.LaunchConstants;
import org.eclipse.wildwebdeveloper.debug.Messages;
import org.eclipse.wildwebdeveloper.embedder.node.NodeJSManager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

@SuppressWarnings("restriction")
public class NodeRunDAPDebugDelegate extends DSPLaunchDelegate {

	public static final String ID = "org.eclipse.wildwebdeveloper.launchConfiguration.nodeDebug"; //$NON-NLS-1$

	// see https://github.com/Microsoft/vscode-node-debug/blob/master/src/node/nodeDebug.ts LaunchRequestArguments
	public static final String ARGUMENTS = "args"; //$NON-NLS-1$
	private static final String CWD = "cwd"; //$NON-NLS-1$
	private static final String ENV = "env"; //$NON-NLS-1$
	private static final String RUNTIME_EXECUTABLE = "runtimeExecutable"; //$NON-NLS-1$

	public static final String NODE_DEBUG_CMD = "/node_modules/node-debug2/out/src/nodeDebug.js"; //$NON-NLS-1$
	public static final String TYPESCRIPT_CONTENT_TYPE = "org.eclipse.wildwebdeveloper.ts"; //$NON-NLS-1$
	public static final String JAVACRIPT_CONTENT_TYPE = "org.eclipse.wildwebdeveloper.js"; //$NON-NLS-1$

	public static final String JAVACRIPT_DEBUGGABLE_PATTERNS = "__debuggablePatterns";
	public static final String JAVACRIPT_DEBUGGABLE_PATTERNS_DEFAULT = "[\"*.js\",\"*.es6\",\"*.jsx\",\"*.mjs\".\"*.cjs\"]";

	
	private static final String TS_CONFIG_NAME = "tsconfig.json"; //$NON-NLS-1$
	private static final String COMPILER_OPTIONS = "compilerOptions"; //$NON-NLS-1$
	private static final String SOURCE_MAP = "sourceMap"; //$NON-NLS-1$
	private static final String SOURCE_MAPS = "sourceMaps"; //$NON-NLS-1$
	private static final String MODULE = "module"; //$NON-NLS-1$
	private static final String MODULE_AMD = "amd"; //$NON-NLS-1$
	private static final String MODULE_SYSTEM = "system"; //$NON-NLS-1$
	private static final String OUT_DIR = "outDir"; //$NON-NLS-1$
	private static final String OUT_FILE = "outFile"; //$NON-NLS-1$
	private static final String ROOT_DIR = "rootDir"; //$NON-NLS-1$

	@Override
	public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
			throws CoreException {
		// user settings
		Map<String, Object> param = new HashMap<>();
		param.put(LaunchConstants.PROGRAM, VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(configuration.getAttribute(LaunchConstants.PROGRAM, "no program path defined"))); //$NON-NLS-1$
		String argsString = configuration.getAttribute(ARGUMENTS, "").trim(); //$NON-NLS-1$
		if (!argsString.isEmpty()) {
			Object[] args = Arrays.asList(argsString.split(" ")).stream() //$NON-NLS-1$
					.filter(s -> !s.trim().isEmpty())
					.map(s -> {
						try {
							return VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(s);
						} catch (CoreException e) {
							IStatus errorStatus = new Status(IStatus.ERROR, Activator.PLUGIN_ID, e.getMessage(), e);
							Activator.getDefault().getLog().log(errorStatus);
							return s;
						}
					})
					.toArray();
			if (args.length > 0) {
				param.put(ARGUMENTS, args);
			}
		}
		Map<String, String> env = configuration.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES,
				Collections.emptyMap());
		if (!env.isEmpty()) {
			JsonObject envJson = new JsonObject();
			for (Entry<String, String> entry : env.entrySet()) {
				envJson.addProperty(entry.getKey(), VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(entry.getValue()));
			}
			param.put(ENV, envJson);
		}
		String cwd = configuration.getAttribute(DebugPlugin.ATTR_WORKING_DIRECTORY, "").trim(); //$NON-NLS-1$
		if (!cwd.isEmpty()) {
			param.put(CWD, VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(cwd));
		}
		File runtimeExecutable = NodeJSManager.getNodeJsLocation();
		if (runtimeExecutable != null) {
			param.put(RUNTIME_EXECUTABLE, runtimeExecutable.getAbsolutePath());
		}
		
		if (!configureAdditionalParameters(param)) {
			return;
		}

		try {
			URL fileURL = FileLocator.toFileURL(
					getClass().getResource(NODE_DEBUG_CMD));
			File file = new File(fileURL.getPath());
			List<String> debugCmdArgs = Collections.singletonList(file.getAbsolutePath());

			DSPLaunchDelegateLaunchBuilder builder = new DSPLaunchDelegateLaunchBuilder(configuration, mode, launch,
					monitor);
			builder.setLaunchDebugAdapter(NodeJSManager.getNodeJsLocation().getAbsolutePath(), debugCmdArgs);
			builder.setMonitorDebugAdapter(configuration.getAttribute(DSPPlugin.ATTR_DSP_MONITOR_DEBUG_ADAPTER, false));
			builder.setDspParameters(param);
			super.launch(builder);
		} catch (IOException e) {
			IStatus errorStatus = new Status(IStatus.ERROR, Activator.PLUGIN_ID, e.getMessage(), e);
			Activator.getDefault().getLog().log(errorStatus);
			Display.getDefault().asyncExec(() -> ErrorDialog.openError(Display.getDefault().getActiveShell(), "Debug error", e.getMessage(), errorStatus)); //$NON-NLS-1$
		}
	}

	private boolean configureAdditionalParameters(Map<String, Object> param) {
		String program = (String)param.get(LaunchConstants.PROGRAM);
		String cwd = (String)param.get(CWD);
		
		if (program == null) {
			return false;
		}
		
		File programFile = new File(program);
		if (Platform.getContentTypeManager().getContentType(TYPESCRIPT_CONTENT_TYPE)
					.isAssociatedWith(programFile.getName())) {
			// TypeScript Source Mappings Configuration
			File parentDirectory = cwd == null ? programFile.getParentFile() : new File(cwd);
			File tsConfigFile = findTSConfigFile(parentDirectory);
			if (tsConfigFile != null && tsConfigFile.exists()) {
				parentDirectory = tsConfigFile.getParentFile();
			}
			
			String errorMessage = null;
			Map<String, Object> tsConfig = readJSonFile(tsConfigFile);
			
			@SuppressWarnings("unchecked")
			Map<String, Object> co = tsConfig == null ? null : (Map<String, Object>)tsConfig.get(COMPILER_OPTIONS);
			if (co == null) {
				errorMessage = Messages.NodeDebug_TSConfirError_NoTsConfig;
				co = new HashMap<>();
			}

			//TS Compiler Options
			param.putAll(co);

			if (errorMessage == null) {
				Object option = co.get(SOURCE_MAP);
				boolean sourceMap  = option instanceof Boolean b && b.booleanValue();
				if (!sourceMap) {
					errorMessage = Messages.NodeDebug_TSConfirError_SourceMapIsNotEnabled;
				}
			}

			// Override "outDir" option by converting it to an absolute path
			boolean outDirOrFileIsSet = false;
			Object option = co.get(MODULE);
			String module = option instanceof String o ? o.trim() : null;
						
			option = co.get(OUT_DIR);
			String outDir = option instanceof String o ? o.trim() : null;
			if (outDir != null && outDir.length() > 0 && !".".equals(outDir) && !"./".equals(outDir)) {
				File outDirFile = new File(parentDirectory, outDir);
				try {
					outDir = outDirFile.getCanonicalPath();
				} catch (IOException e) {
					// Default to an absolute file path (non-checked)
					outDir = outDirFile.getAbsolutePath();
				}
				param.put(OUT_DIR, outDir);
				outDirOrFileIsSet = true;
			}
			
			option = co.get(OUT_FILE);
			String outFile = option instanceof String  o ? o.trim() : null;
			if (outFile != null && outFile.length() != 0) {
				File outFileFile = new File(parentDirectory, outFile);
				try {
					outFile = outFileFile.getCanonicalPath();
				} catch (IOException e) {
					// Default to an absolute file path (non-checked)
					outFile = outFileFile.getAbsolutePath();
				}
				param.put(OUT_FILE, outFile);
				outDirOrFileIsSet = true;
				
				if (!MODULE_AMD.equalsIgnoreCase(module)  && !MODULE_SYSTEM.equalsIgnoreCase(module)) {
					errorMessage = Messages.NodeDebug_TSConfigError_OutDirNotSupportedModule;
				}
			}
			
			option = co.get(ROOT_DIR);
			String rootDir = option instanceof String o ? o.trim() : null;
			if (rootDir != null && rootDir.length() > 0 && !".".equals(outDir) && !"./".equals(outDir)) {
				File rootDirFile = new File(parentDirectory, rootDir);
				try {
					rootDir = rootDirFile.getCanonicalPath();
				} catch (IOException e) {
					// Default to an absolute file path (non-checked)
					rootDir = rootDirFile.getAbsolutePath();
				}
				param.put(ROOT_DIR, rootDir);
			}
			
			if (!outDirOrFileIsSet && errorMessage == null) {
				errorMessage = Messages.NodeDebug_TSConfigError_OutDirIsNotSet;
			}

			if (errorMessage != null) {
				// Display error message
				final int[] result = new int[1];
				final String dialogMessage = errorMessage;
				final String editTSConfig = tsConfigFile.exists() && tsConfigFile.isFile() ?
						Messages.NodeDebug_TSConfirError_OpenTSConfigInEditor :
							Messages.NodeDebug_TSConfirError_CreateAndOpenTSConfigInEditor;
				final File directory = parentDirectory;
				
				Display.getDefault().syncExec(() -> {
					MessageDialog dialog = new MessageDialog(DebugUIPlugin.getShell(),
							Messages.NodeDebug_TSConfirError_Title, null, dialogMessage, MessageDialog.QUESTION_WITH_CANCEL,
							2, editTSConfig,
							Messages.NodeDebug_TSConfirError_StartDebuggingAsIs, Messages.NodeDebug_TSConfirError_Cancel);
					result[0] = dialog.open();
				});
				
				if (result[0] == 0) {
					// Open TSConfig in editor
					Display.getDefault().asyncExec(new Runnable() {
						@Override
						public void run() {
							IFile file = createNewEmptyFile(new File(directory, TS_CONFIG_NAME));
							if (file != null) {
								try {
									IDE.openEditor(
										PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), 
										new FileEditorInput(file), 
										"org.eclipse.ui.genericeditor.GenericEditor",
										true);
								} catch (PartInitException e1) {
									Activator.getDefault().getLog().error(e1.getMessage(), e1);
								}
							}
						}

						private IFile createNewEmptyFile(File fsFile) {
							IWorkspace ws = ResourcesPlugin.getWorkspace();
							IWorkspaceRoot wr = ws.getRoot();
							IFile file = wr.getFileForLocation(new Path(fsFile.getAbsolutePath()));
							if (!(file.exists() && file.isAccessible())) {
								IFile[] result = new IFile[1];
								try {
									ws.run((IWorkspaceRunnable) monitor -> {
										result[0] = null;
										try (ByteArrayInputStream is = new ByteArrayInputStream(new byte[0])) {
											createContainers(file);
											file.create(is, true, null);
											file.refreshLocal(IResource.DEPTH_ZERO, null);
											result[0] = file;
										} catch (CoreException | IOException e) {
											Activator.getDefault().getLog().error(e.getMessage(), e);
										}
									  }, null);								
								} catch (CoreException e) {
									Activator.getDefault().getLog().error(e.getMessage(), e);
								}
								return result[0];
							}
							return file;
						}

						void createContainers(IResource resource) throws CoreException {
							IContainer container= resource.getParent();
							if (container instanceof IFolder parent && !parent.exists()) {
								createContainers(parent);
								parent.create(false, true, null);
							}
						}
					});
				} else if (result[0] == 1) {
					// Start debugging as is
					return true;
				}
				return false;
			}
			
			return true;
		} else if (Platform.getContentTypeManager().getContentType(JAVACRIPT_CONTENT_TYPE)
				.isAssociatedWith(programFile.getName())) {

			// JavaScript configuration
			
			// workaround until
			// https://github.com/microsoft/vscode-node-debug2/commit/f2dfa4ca4026fb3e4f143a391270a03df8187b42#diff-d03a74f75ec189cbc7dd3d2e105fc9c9R625
			// is released in VSCode
			param.put(SOURCE_MAPS, false);
			param.put(JAVACRIPT_DEBUGGABLE_PATTERNS, JAVACRIPT_DEBUGGABLE_PATTERNS_DEFAULT);
			
			return true;
		}
		return false;
	}

	private File findTSConfigFile(File parentDirectory) {
		File tsConfigFile;
		do {
			tsConfigFile = new File(parentDirectory, TS_CONFIG_NAME);
			if (tsConfigFile.isFile()) {
				return tsConfigFile;
			}
			parentDirectory = parentDirectory.getParentFile();
		} while (parentDirectory != null && parentDirectory.isDirectory());
		return null;
	}

	private static final Pattern BlockCommentPattern = Pattern.compile("(?<!//.*)/\\*(?:.|\\R)*?\\*/");
	private static final Pattern LineCommentPattern = Pattern.compile("\\s*//.*");
	private static final Pattern TrailingCommaPattern = Pattern.compile(",(\\s*)\\}");

	/**
	 * Given a string representing the content of a tsconfig.json file, modify the
	 * string so that it may be safely passed to {@link Gson#fromJson} for parsing.
	 * The resulting string will be semantically equivalent to the original content.
	 * @param tsConfgContent A copy of a tsconfig.json file's content.
	 * @return A modified version of the tsconfig.json content.
	 */
	private String getSanitisedTSConfigForGson(String tsConfgContent) {
		tsConfgContent = BlockCommentPattern.matcher(tsConfgContent).replaceAll("");
		tsConfgContent = LineCommentPattern.matcher(tsConfgContent).replaceAll("");
		tsConfgContent = TrailingCommaPattern.matcher(tsConfgContent).replaceAll("$1}");
		return tsConfgContent;
	}

	public Map<String, Object> readJSonFile(File tsConfgFile) {
		if (tsConfgFile == null || !tsConfgFile.isFile()) {
			return Map.of();
		}
		try (BufferedReader in = new BufferedReader(new FileReader(tsConfgFile))) {
			String inputLine;
			StringBuffer response = new StringBuffer();
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine).append('\n');
			}
			Type type = new TypeToken<Map<String, Object>>() {}.getType();
			return new Gson().fromJson(getSanitisedTSConfigForGson(response.toString()), type);
		} catch (IOException e) {
			return Map.of();
		}
	}
}
