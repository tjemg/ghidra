/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.gui.objects.components;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.beans.*;
import java.math.BigInteger;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualLinkedHashBidiMap;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.text.StringEscapeUtils;
import org.jdom.Element;

import docking.DialogComponentProvider;
import ghidra.app.plugin.core.debug.gui.DebuggerResources;
import ghidra.app.plugin.core.debug.utils.MiscellaneousUtils;
import ghidra.dbg.target.TargetMethod;
import ghidra.dbg.target.TargetMethod.ParameterDescription;
import ghidra.framework.options.SaveState;
import ghidra.framework.plugintool.AutoConfigState.ConfigStateField;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.Msg;
import ghidra.util.layout.PairLayout;

public class DebuggerMethodInvocationDialog extends DialogComponentProvider
		implements PropertyChangeListener {

	public static class BigIntEditor extends PropertyEditorSupport {
		@Override
		public String getJavaInitializationString() {
			Object value = getValue();
			return value == null
					? "null"
					: "new BigInteger(\"%s\")".formatted(value);
		}

		@Override
		public void setAsText(String text) throws IllegalArgumentException {
			setValue(text == null
					? null
					: new BigInteger(text));
		}
	}

	static {
		PropertyEditorManager.registerEditor(BigInteger.class, BigIntEditor.class);
	}

	private static final String KEY_MEMORIZED_ARGUMENTS = "memorizedArguments";

	static class ChoicesPropertyEditor implements PropertyEditor {
		private final List<?> choices;
		private final String[] tags;

		private final List<PropertyChangeListener> listeners = new ArrayList<>();

		private Object value;

		public ChoicesPropertyEditor(Set<?> choices) {
			this.choices = List.copyOf(choices);
			this.tags = choices.stream().map(Objects::toString).toArray(String[]::new);
		}

		@Override
		public void setValue(Object value) {
			if (Objects.equals(value, this.value)) {
				return;
			}
			if (!choices.contains(value)) {
				throw new IllegalArgumentException("Unsupported value: " + value);
			}
			Object oldValue;
			List<PropertyChangeListener> listeners;
			synchronized (this.listeners) {
				oldValue = this.value;
				this.value = value;
				if (this.listeners.isEmpty()) {
					return;
				}
				listeners = List.copyOf(this.listeners);
			}
			PropertyChangeEvent evt = new PropertyChangeEvent(this, null, oldValue, value);
			for (PropertyChangeListener l : listeners) {
				l.propertyChange(evt);
			}
		}

		@Override
		public Object getValue() {
			return value;
		}

		@Override
		public boolean isPaintable() {
			return false;
		}

		@Override
		public void paintValue(Graphics gfx, Rectangle box) {
			// Not paintable
		}

		@Override
		public String getJavaInitializationString() {
			if (value == null) {
				return "null";
			}
			if (value instanceof String str) {
				return "\"" + StringEscapeUtils.escapeJava(str) + "\"";
			}
			return Objects.toString(value);
		}

		@Override
		public String getAsText() {
			return Objects.toString(value);
		}

		@Override
		public void setAsText(String text) throws IllegalArgumentException {
			int index = ArrayUtils.indexOf(tags, text);
			if (index < 0) {
				throw new IllegalArgumentException("Unsupported value: " + text);
			}
			setValue(choices.get(index));
		}

		@Override
		public String[] getTags() {
			return tags.clone();
		}

		@Override
		public Component getCustomEditor() {
			return null;
		}

		@Override
		public boolean supportsCustomEditor() {
			return false;
		}

		@Override
		public void addPropertyChangeListener(PropertyChangeListener listener) {
			synchronized (listeners) {
				listeners.add(listener);
			}
		}

		@Override
		public void removePropertyChangeListener(PropertyChangeListener listener) {
			synchronized (listeners) {
				listeners.remove(listener);
			}
		}
	}

	final static class NameTypePair extends MutablePair<String, Class<?>> {

		public static NameTypePair fromParameter(ParameterDescription<?> parameter) {
			return new NameTypePair(parameter.name, parameter.type);
		}

		public static NameTypePair fromString(String name) throws ClassNotFoundException {
			String[] parts = name.split(",", 2);
			if (parts.length != 2) {
				// This appears to be a bad assumption - empty fields results in solitary labels
				return new NameTypePair(parts[0], String.class);
				//throw new IllegalArgumentException("Could not parse name,type");
			}
			return new NameTypePair(parts[0], Class.forName(parts[1]));
		}

		public NameTypePair(String name, Class<?> type) {
			super(name, type);
		}

		@Override
		public String toString() {
			return getName() + "," + getType().getName();
		}

		@Override
		public Class<?> setValue(Class<?> value) {
			throw new UnsupportedOperationException();
		}

		public String getName() {
			return getLeft();
		}

		public Class<?> getType() {
			return getRight();
		}
	}

	private final BidiMap<ParameterDescription<?>, PropertyEditor> paramEditors =
		new DualLinkedHashBidiMap<>();

	private JPanel panel;
	private JLabel descriptionLabel;
	private JPanel pairPanel;
	private PairLayout layout;

	protected JButton invokeButton;
	protected JButton resetButton;
	protected boolean resetRequested;

	private final PluginTool tool;
	Map<String, ParameterDescription<?>> parameters;

	// TODO: Not sure this is the best keying, but I think it works.
	private Map<NameTypePair, Object> memorized = new HashMap<>();
	private Map<String, ?> arguments;

	public DebuggerMethodInvocationDialog(PluginTool tool, String title, String buttonText,
			Icon buttonIcon) {
		super(title, true, true, true, false);
		this.tool = tool;

		populateComponents(buttonText, buttonIcon);
		setRememberSize(false);
	}

	protected Object computeMemorizedValue(ParameterDescription<?> parameter) {
		return memorized.computeIfAbsent(NameTypePair.fromParameter(parameter),
			ntp -> parameter.defaultValue);
	}

	public Map<String, ?> promptArguments(Map<String, ParameterDescription<?>> parameterMap) {
		setParameters(parameterMap);
		tool.showDialog(this);

		return getArguments();
	}

	public void setParameters(Map<String, ParameterDescription<?>> parameterMap) {
		this.parameters = parameterMap;
		populateOptions();
	}

	private void populateComponents(String buttonText, Icon buttonIcon) {
		panel = new JPanel(new BorderLayout());
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));

		layout = new PairLayout(5, 5);
		pairPanel = new JPanel(layout);

		JPanel centering = new JPanel(new FlowLayout(FlowLayout.CENTER));
		JScrollPane scrolling = new JScrollPane(centering, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		//scrolling.setPreferredSize(new Dimension(100, 130));
		panel.add(scrolling, BorderLayout.CENTER);
		centering.add(pairPanel);

		descriptionLabel = new JLabel();
		descriptionLabel.setMaximumSize(new Dimension(300, 100));
		panel.add(descriptionLabel, BorderLayout.NORTH);

		addWorkPanel(panel);

		invokeButton = new JButton(buttonText, buttonIcon);
		addButton(invokeButton);
		resetButton = new JButton("Reset", DebuggerResources.ICON_REFRESH);
		addButton(resetButton);
		addCancelButton();

		invokeButton.addActionListener(this::invoke);
		resetButton.addActionListener(this::reset);
		resetRequested = false;
	}

	@Override
	protected void cancelCallback() {
		this.arguments = null;
		this.resetRequested = false;
		close();
	}

	void invoke(ActionEvent evt) {
		this.arguments = TargetMethod.validateArguments(parameters, collectArguments(), false);
		this.resetRequested = false;
		close();
	}

	void reset(ActionEvent evt) {
		this.arguments = new LinkedHashMap<>();
		this.resetRequested = true;
		close();
	}

	protected PropertyEditor getEditor(ParameterDescription<?> param) {
		if (!param.choices.isEmpty()) {
			return new ChoicesPropertyEditor(param.choices);
		}
		Class<?> type = param.type;
		PropertyEditor editor = PropertyEditorManager.findEditor(type);
		if (editor != null) {
			return editor;
		}
		Msg.warn(this, "No editor for " + type + "? Trying String instead");
		return PropertyEditorManager.findEditor(String.class);
	}

	void populateOptions() {
		pairPanel.removeAll();
		paramEditors.clear();
		for (ParameterDescription<?> param : parameters.values()) {
			JLabel label = new JLabel(param.display);
			label.setToolTipText(param.description);
			pairPanel.add(label);

			PropertyEditor editor = getEditor(param);
			Object val = computeMemorizedValue(param);
			editor.setValue(val);
			editor.addPropertyChangeListener(this);
			pairPanel.add(MiscellaneousUtils.getEditorComponent(editor));
			paramEditors.put(param, editor);
		}
	}

	protected Map<String, ?> collectArguments() {
		Map<String, Object> map = new LinkedHashMap<>();
		for (ParameterDescription<?> param : paramEditors.keySet()) {
			Object val = memorized.get(NameTypePair.fromParameter(param));
			if (val != null) {
				map.put(param.name, val);
			}
		}
		return map;
	}

	public Map<String, ?> getArguments() {
		return arguments;
	}

	public <T> void setMemorizedArgument(String name, Class<T> type, T value) {
		if (value == null) {
			return;
		}
		memorized.put(new NameTypePair(name, type), value);
	}

	public <T> T getMemorizedArgument(String name, Class<T> type) {
		return type.cast(memorized.get(new NameTypePair(name, type)));
	}

	public void forgetMemorizedArguments() {
		memorized.clear();
	}

	@Override
	public void propertyChange(PropertyChangeEvent evt) {
		PropertyEditor editor = (PropertyEditor) evt.getSource();
		ParameterDescription<?> param = paramEditors.getKey(editor);
		memorized.put(NameTypePair.fromParameter(param), editor.getValue());
	}

	public void writeConfigState(SaveState saveState) {
		SaveState subState = new SaveState();
		for (Map.Entry<NameTypePair, Object> ent : memorized.entrySet()) {
			NameTypePair ntp = ent.getKey();
			ConfigStateField.putState(subState, ntp.getType().asSubclass(Object.class),
				ntp.getName(), ent.getValue());
		}
		saveState.putXmlElement(KEY_MEMORIZED_ARGUMENTS, subState.saveToXml());
	}

	public void readConfigState(SaveState saveState) {
		Element element = saveState.getXmlElement(KEY_MEMORIZED_ARGUMENTS);
		if (element == null) {
			return;
		}
		SaveState subState = new SaveState(element);
		for (String name : subState.getNames()) {
			try {
				NameTypePair ntp = NameTypePair.fromString(name);
				memorized.put(ntp,
					ConfigStateField.getState(subState, ntp.getType(), ntp.getName()));
			}
			catch (Exception e) {
				Msg.error(this, "Error restoring memorized parameter " + name, e);
			}
		}
	}

	public boolean isResetRequested() {
		return resetRequested;
	}

	public void setDescription(String htmlDescription) {
		if (htmlDescription == null) {
			descriptionLabel.setBorder(BorderFactory.createEmptyBorder());
			descriptionLabel.setText("");
		}
		else {
			descriptionLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
			descriptionLabel.setText(htmlDescription);
		}
	}
}
