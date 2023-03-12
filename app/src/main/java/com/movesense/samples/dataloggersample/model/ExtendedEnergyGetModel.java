package com.movesense.samples.dataloggersample.model;


import com.google.gson.annotations.SerializedName;

public class ExtendedEnergyGetModel {

    @SerializedName("Content")
    public final Content mContent;

    public ExtendedEnergyGetModel(Content content){
        mContent = content;
    }

    public Content getContent() {
        return mContent;
    }

    public class Content {

        @SerializedName("Percent")
        private String percent;

        @SerializedName("MilliVoltages")
        private String milliVoltages;

        @SerializedName("InternalResistance")
        private String internalResistance;

        public Content(String percent, String milliVoltages, String internalResistance) {
            this.percent = percent;
            this.milliVoltages = milliVoltages;
            this.internalResistance = internalResistance;
        }

        public String getPercent() {
            return percent;
        }

        public String getMilliVoltages() {
            return milliVoltages;
        }

        public String getInternalResistance() {
            return internalResistance;
        }
    }

}
