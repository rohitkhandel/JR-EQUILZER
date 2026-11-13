# Android manifest components are retained by AGP's manifest-derived rules.
# Preserve JSON-serialized DSP models and enum names used by user-imported preset files.
-keep class com.jr.equalizer.dsp.DspSettings { *; }
-keep class com.jr.equalizer.dsp.EqBand { *; }
-keep class com.jr.equalizer.dsp.CompressorSettings { *; }
-keep class com.jr.equalizer.dsp.FilterType { *; }
