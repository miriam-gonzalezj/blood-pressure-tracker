package com.mtagj.bptracker.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mtagj.bptracker.R;
import com.mtagj.bptracker.data.MeasurementWithDeltas;
import com.mtagj.bptracker.util.DateTimeUtils;

import java.util.ArrayList;
import java.util.List;

public class MeasurementAdapter extends RecyclerView.Adapter<MeasurementAdapter.ViewHolder> {

    public interface Listener {
        void onEdit(MeasurementWithDeltas item);
        void onDelete(MeasurementWithDeltas item);
    }

    private final List<MeasurementWithDeltas> items = new ArrayList<>();
    private final Listener listener;

    public MeasurementAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<MeasurementWithDeltas> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_measurement, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MeasurementWithDeltas item = items.get(position);
        holder.bind(item, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvDateTime, tvAltitude, tvValues, tvDeltas, tvNote;
        final View btnEdit, btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDateTime = itemView.findViewById(R.id.tvDateTime);
            tvAltitude = itemView.findViewById(R.id.tvAltitude);
            tvValues = itemView.findViewById(R.id.tvValues);
            tvDeltas = itemView.findViewById(R.id.tvDeltas);
            tvNote = itemView.findViewById(R.id.tvNote);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }

        void bind(MeasurementWithDeltas item, Listener listener) {
            long ts = item.measurement.timestampMillis;
            tvDateTime.setText(DateTimeUtils.formatDate(ts) + "  " + DateTimeUtils.formatTime(ts));
            tvAltitude.setText(item.measurement.altitude == null ? "" : item.measurement.altitude);
            tvValues.setText(item.measurement.systolic + "/" + item.measurement.diastolic + " mmHg  ·  "
                    + item.measurement.pulse + " lpm  ·  " + item.measurement.arm);

            if (item.daysElapsed != null) {
                String sysDelta = (item.varSystolic >= 0 ? "+" : "") + item.varSystolic;
                String diaDelta = (item.varDiastolic >= 0 ? "+" : "") + item.varDiastolic;
                tvDeltas.setVisibility(View.VISIBLE);
                tvDeltas.setText("Δ sist " + sysDelta + "   Δ diast " + diaDelta
                        + "   (" + item.daysElapsed + " día" + (item.daysElapsed == 1 ? "" : "s") + " desde la anterior)");
            } else {
                tvDeltas.setVisibility(View.GONE);
            }

            if (item.measurement.note != null && !item.measurement.note.trim().isEmpty()) {
                tvNote.setVisibility(View.VISIBLE);
                tvNote.setText(item.measurement.note);
            } else {
                tvNote.setVisibility(View.GONE);
            }

            btnEdit.setOnClickListener(v -> listener.onEdit(item));
            btnDelete.setOnClickListener(v -> listener.onDelete(item));
        }
    }
}
