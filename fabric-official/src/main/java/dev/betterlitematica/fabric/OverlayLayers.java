package dev.betterlitematica.fabric;
/** Render policy is copied into each overlay batch. */
final class OverlayLayers {
 record Layer(boolean face,boolean through){}
 private static final Layer[] LINES={new Layer(false,false),new Layer(false,true)},FACES={new Layer(true,false),new Layer(true,true)};
 static Layer lines(boolean through){return LINES[through?1:0];}static Layer faces(boolean through){return FACES[through?1:0];}
}
