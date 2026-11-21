package info.openrocket.swing.wyvern.ui;

import java.awt.Component;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;

import info.openrocket.core.unit.Unit;

/**
 * A table model that shows one object per row and is described by a list of columns
 * with getter and setter functions. Number columns hold SI values and convert to and
 * from a display unit, which is shown in the column header.
 *
 * @param <T> the row type
 */
public class RowTableModel<T> extends AbstractTableModel {

	/** One column of a {@link RowTableModel}. */
	public static final class Column<T> {
		private final String title;
		private final Class<?> type;
		private final Function<T, Object> getter;
		private final BiConsumer<T, Object> setter;

		private Column(String title, Class<?> type, Function<T, Object> getter, BiConsumer<T, Object> setter) {
			this.title = title;
			this.type = type;
			this.getter = getter;
			this.setter = setter;
		}
	}

	/** A text column; pass a null setter for a read-only column. */
	public static <T> Column<T> text(String title, Function<T, String> getter, BiConsumer<T, String> setter) {
		return new Column<>(title, String.class, getter::apply,
				setter == null ? null : (row, value) -> setter.accept(row, value == null ? "" : value.toString().trim()));
	}

	/**
	 * A number column backed by an SI value and shown in a unit. NaN shows as an empty
	 * cell. Pass a null unit to show the raw value and a null setter for a read-only column.
	 */
	public static <T> Column<T> number(String title, Unit unit, ToDoubleFunction<T> getter, ObjDoubleConsumer<T> setter) {
		String header = (unit == null || unit.getUnit().isBlank()) ? title : title + " (" + unit.getUnit().trim() + ")";
		return new Column<>(header, Double.class,
				row -> {
					double value = getter.applyAsDouble(row);
					if (Double.isNaN(value) || Double.isInfinite(value)) {
						return null;
					}
					return unit == null ? value : unit.toUnit(value);
				},
				setter == null ? null : (row, value) -> {
					if (value instanceof Number number && !Double.isNaN(number.doubleValue())) {
						double shown = number.doubleValue();
						setter.accept(row, unit == null ? shown : unit.fromUnit(shown));
					}
				});
	}

	/** A whole number column; pass a null setter for a read-only column. */
	public static <T> Column<T> integer(String title, Function<T, Integer> getter, BiConsumer<T, Integer> setter) {
		return new Column<>(title, Integer.class, getter::apply,
				setter == null ? null : (row, value) -> {
					if (value instanceof Number number) {
						setter.accept(row, number.intValue());
					}
				});
	}

	private final Supplier<List<T>> rows;
	private final Runnable onEdit;
	private List<Column<T>> columns = new ArrayList<>();

	/**
	 * @param rows   supplies the current rows every time the table asks
	 * @param onEdit called after a cell was edited; may be null
	 */
	public RowTableModel(Supplier<List<T>> rows, Runnable onEdit) {
		this.rows = rows;
		this.onEdit = onEdit;
	}

	/** Replace the columns and tell the table that its structure changed. */
	public void setColumns(List<Column<T>> columns) {
		this.columns = new ArrayList<>(columns);
		fireTableStructureChanged();
	}

	public T getRow(int row) {
		return rows.get().get(row);
	}

	@Override
	public int getRowCount() {
		return rows.get().size();
	}

	@Override
	public int getColumnCount() {
		return columns.size();
	}

	@Override
	public String getColumnName(int column) {
		return columns.get(column).title;
	}

	@Override
	public Class<?> getColumnClass(int column) {
		return columns.get(column).type;
	}

	@Override
	public boolean isCellEditable(int row, int column) {
		return columns.get(column).setter != null;
	}

	@Override
	public Object getValueAt(int row, int column) {
		return columns.get(column).getter.apply(getRow(row));
	}

	@Override
	public void setValueAt(Object value, int row, int column) {
		Column<T> c = columns.get(column);
		if (c.setter == null || row >= getRowCount()) {
			return;
		}
		c.setter.accept(getRow(row), value);
		if (onEdit != null) {
			onEdit.run();
		}
	}

	/** Create a table for a model, with numbers right aligned and shown to about four significant digits. */
	public static <T> JTable createTable(RowTableModel<T> model) {
		JTable table = new JTable(model) {
			@Override
			public boolean editCellAt(int row, int column, java.util.EventObject e) {
				boolean editing = super.editCellAt(row, column, e);
				// Typing into a cell replaces its value instead of appending to it
				if (editing && getEditorComponent() instanceof JTextComponent text) {
					text.selectAll();
					if (e instanceof java.awt.event.MouseEvent) {
						// The click that opened the editor would otherwise place the caret
						SwingUtilities.invokeLater(text::selectAll);
					}
				}
				return editing;
			}
		};
		table.setRowHeight(table.getRowHeight() + 6);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Double.class, new NumberRenderer());
		return table;
	}

	/** Format a number with about four significant digits. */
	public static String format(double value) {
		double abs = Math.abs(value);
		String pattern;
		if (abs >= 1000) {
			pattern = "#,##0";
		} else if (abs >= 100) {
			pattern = "0.0";
		} else if (abs >= 10) {
			pattern = "0.00";
		} else {
			pattern = "0.000";
		}
		return new DecimalFormat(pattern).format(value);
	}

	private static final class NumberRenderer extends DefaultTableCellRenderer {
		NumberRenderer() {
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			Object shown = (value instanceof Number number) ? format(number.doubleValue()) : "";
			return super.getTableCellRendererComponent(table, shown, isSelected, hasFocus, row, column);
		}
	}
}
