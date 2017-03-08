import java.util.concurrent.atomic.AtomicInteger;

import ij.ImageStack;

/**
 **
 ** ImageDtt - Process images with DTT-based methods
 **
 ** Copyright (C) 2016 Elphel, Inc.
 **
 ** -----------------------------------------------------------------------------**
 **  
 **  ImageDtt.java is free software: you can redistribute it and/or modify
 **  it under the terms of the GNU General Public License as published by
 **  the Free Software Foundation, either version 3 of the License, or
 **  (at your option) any later version.
 **
 **  This program is distributed in the hope that it will be useful,
 **  but WITHOUT ANY WARRANTY; without even the implied warranty of
 **  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 **  GNU General Public License for more details.
 **
 **  You should have received a copy of the GNU General Public License
 **  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ** -----------------------------------------------------------------------------**
 **
 */

public class ImageDtt {
	
	  static double [] kern_g={
			  0.0,   0.125,  0.0  ,
			  0.125, 0.5,    0.125,
			  0.0,   0.125,  0.0  };
	  static double [] kern_rb={
			  0.0625,  0.125, 0.0625,
			  0.125,   0.25,  0.125,
			  0.0625,  0.125, 0.0625};
//	  static double [][] kerns = {kern_rb,kern_rb,kern_g};
	  static int [][] corn_side_indices = { // of 012/345/678 3x3 square kernel
			  {4,5,7,8},           // top left corner
			  {3,4,5,6,7,8},       // top middle
			  {3,4,6,7},           // top right
			  {1,2,4,5,7,8},       // middle left
			  {0,1,2,3,4,5,6,7,8}, // middle
			  {0,1,3,4,6,7},       // middle right
			  {1,2,4,5},           // bottom left
			  {0,1,2,3,4,5},       // mottom middle
			  {0,1,3,4}};          // bottom right
//	 public static int FORCE_DISPARITY_BIT = 8; // move to parameters?
	  static int  DISPARITY_INDEX_INT =            0; // 0 - disparity from correlation integer pixels, 1 - ortho
	  static int  DISPARITY_INDEX_CM =             2; // 2 - disparity from correlation "center mass", 3 - ortho (only used for fine correction)
	  static int  DISPARITY_INDEX_HOR =            4; // disparity from correlation of the horizontal pairs with center suppressed
	  static int  DISPARITY_INDEX_HOR_STRENGTH =   5; // strength for hor mode (emphasis on vertical lines)
	  static int  DISPARITY_INDEX_VERT =           6; // disparity from correlation of the vertical pairs with center suppressed
	  static int  DISPARITY_INDEX_VERT_STRENGTH =  7; // strength in vert mode (horizontal lines detection)
	  static int  DISPARITY_INDEX_POLY =           8; // index of disparity value in disparity_map == 2 (0,2 or 4)
	  static int  DISPARITY_STRENGTH_INDEX =      10; // index of strength data in disparity map ==6
	  static int  DISPARITY_VARIATIONS_INDEX =    11; // index of strength data in disparity map ==6
	  static int  IMG_DIFF0_INDEX =               12; // index of noise- normalized image difference for port 0 in disparity map
	  static String [] DISPARITY_TITLES = {
			  "int_disp","int_y_disp","cm_disp","cm_y_disp","hor_disp","hor_strength","vert_disp","vert_strength",
			  "poly_disp", "poly_y_disp", "strength_disp", "vary_disp","diff0","diff1","diff2","diff3"};
	  
	  static int  TCORR_COMBO_RSLT =  0; // normal combined correlation from all   selected pairs (mult/sum)
	  static int  TCORR_COMBO_SUM =   1; // sum of channle correlations from all   selected pairs
	  static int  TCORR_COMBO_HOR =   2; // combined correlation from 2 horizontal pairs (0,1). Used to detect vertical features
	  static int  TCORR_COMBO_VERT =  3; // combined correlation from 2 vertical   pairs (0,1). Used to detect horizontal features
	  static String [] TCORR_TITLES = {"combo","sum","hor","vert"};
	  
	  
	 
     public static int getImgMask  (int data){ return (data & 0xf);}      // which images to use
     public static int getPairMask (int data){ return ((data >> 4) & 0xf);} // which pairs to combine in the combo:  1 - top, 2 bottom, 4 - left, 8 - right
     public static int setImgMask  (int data, int mask) {return (data & ~0xf) | (mask & 0xf);}
     public static int setPairMask (int data, int mask) {return (data & ~0xf0) | ((mask & 0xf) << 4);}
     public static boolean getForcedDisparity (int data){return (data & 0x100) != 0;}
     public static int     setForcedDisparity (int data, boolean force) {return (data & ~0x100) | (force?0x100:0);}
     public static boolean getOrthoLines (int data){return (data & 0x200) != 0;}
     public static int     setOrthoLines (int data, boolean force) {return (data & ~0x200) | (force?0x200:0);}
	
	public ImageDtt(){

	}

	public double [][][][] mdctStack(
			final ImageStack                                 imageStack,
			final int                                        subcamera, // 
			final EyesisCorrectionParameters.DCTParameters   dctParameters, //
			final EyesisDCT                                  eyesisDCT,
			final int                                        threadsMax, // maximal step in pixels on the maxRadius for 1 angular step (i.e. 0.5)
			final int                                        debugLevel,
			final boolean                                    updateStatus) // update status info

	{
	  	  if (imageStack==null) return null;
		  final int imgWidth=imageStack.getWidth();
		  final int nChn=imageStack.getSize();
		  double [][][][] dct_data = new double [nChn][][][];
		  float [] fpixels;
		  int i,chn; //tileX,tileY;
		  /* find number of the green channel - should be called "green", if none - use last */
		  // Extract float pixels from inage stack, convert each to double

		  EyesisDCT.DCTKernels dct_kernels = null;
		  dct_kernels = ((eyesisDCT==null) || (eyesisDCT.kernels==null))?null:eyesisDCT.kernels[subcamera];
		  if (dct_kernels == null){
			  System.out.println("No DCT kernels available for subcamera # "+subcamera);
		  } else if (debugLevel>0){
			  System.out.println("Using DCT kernels for subcamera # "+subcamera);
		  }
//		  if (dctParameters.kernel_chn >=0 ){
//			  dct_kernels = eyesisDCT.kernels[dctParameters.kernel_chn];
//		  }
		  
		  for (chn=0;chn<nChn;chn++) {
			  fpixels= (float[]) imageStack.getPixels(chn+1);
			  double[] dpixels = new double[fpixels.length];
			  for (i = 0; i <fpixels.length;i++) dpixels[i] = fpixels[i];
			  // convert each to DCT tiles
			  dct_data[chn] =lapped_dct(
						dpixels,
						imgWidth,
						dctParameters.dct_size,
						0, //     dct_mode,    // 0: dct/dct, 1: dct/dst, 2: dst/dct, 3: dst/dst
						dctParameters.dct_window, // final int       window_type,
						chn,
						dct_kernels,
						dctParameters.skip_sym,
						dctParameters.convolve_direct,
						dctParameters.tileX,
						dctParameters.tileY,
						dctParameters.dbg_mode,
						threadsMax,  // maximal number of threads to launch                         
						debugLevel);
		  }
		return dct_data;
	}
	
	public double [][][] lapped_dct(
			final double [] dpixels,
			final int       width,
			final int       dct_size,
			final int       dct_mode,    // 0: dct/dct, 1: dct/dst, 2: dst/dct, 3: dst/dst
			final int       window_type,
			final int       color,
			final EyesisDCT.DCTKernels dct_kernels,
			final boolean   skip_sym,
			final boolean   convolve_direct, // test feature - convolve directly with the symmetrical kernel
			final int       debug_tileX,
			final int       debug_tileY,
			final int       debug_mode,
			final int       threadsMax,  // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
		final int kernel_margin = 1; //move to parameters?
		final int height=dpixels.length/width;
		final int tilesX=width/dct_size-1;
		final int tilesY=height/dct_size-1;
		final int nTiles=tilesX*tilesY; 
		final double [][][] dct_data = new double[tilesY][tilesX][dct_size*dct_size];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int tileY = 0; tileY < tilesY; tileY++){
			for (int tileX = 0; tileX < tilesX; tileX++){
				for (int i=0; i<dct_data[tileY][tileX].length;i++) dct_data[tileY][tileX][i]= 0.0; // actually not needed, Java initializes arrays
			}
		}
		double [] dc = new double [dct_size*dct_size];
		for (int i = 0; i<dc.length; i++) dc[i] = 1.0;
		DttRad2 dtt0 = new DttRad2(dct_size);
		dtt0.set_window(window_type);
		final double [] dciii = dtt0.dttt_iii  (dc, dct_size);
		final double [] dciiie = dtt0.dttt_iiie  (dc, 0, dct_size);
		if ((globalDebugLevel > 0) && (color ==2)) {
			double [][]dcx = {dc,dciii,dciiie, dtt0.dttt_ii(dc, dct_size),dtt0.dttt_iie(dc, 0, dct_size)}; 
			showDoubleFloatArrays sdfa_instance0 = new showDoubleFloatArrays(); // just for debugging?
			sdfa_instance0.showArrays(dcx,  dct_size, dct_size, true, "dcx");
		}

		
		if (globalDebugLevel > 0) {
			System.out.println("lapped_dctdc(): width="+width+" height="+height);
		}

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(dct_size);
					dtt.set_window(window_type);
					double [] tile_in = new double[4*dct_size * dct_size];
					double [] sym_conv= null;
					if ((dct_kernels != null) && convolve_direct){ // debug feature - directly convolve with symmetrical kernel
						sym_conv = new double[4*dct_size * dct_size];
					}
					double [] tile_folded;
					double [] tile_out; // = new double[dct_size * dct_size];
					int tileY,tileX;
					int n2 = dct_size * 2;
					double [] tile_out_copy = null;
					showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						int kernelTileY=0;
						int kernelTileX=0;
						//readDCTKernels() debugLevel = 1 kernels[0].size = 8 kernels[0].img_step = 16 kernels[0].asym_nonzero = 4 nColors = 3 numVert = 123 numHor =  164
						if (dct_kernels != null){ // convolve directly with asym_kernel
							int asym_center = dct_kernels.asym_size/2; // 7 for 15
							kernelTileY = kernel_margin + (tileY * dct_size) / dct_kernels.img_step;
							kernelTileX = kernel_margin + (tileX * dct_size) / dct_kernels.img_step;
							if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
								System.out.println("kernelTileY="+kernelTileY+" kernelTileX="+kernelTileX+" width="+width);
							}
							for (int i = 0; i < n2; i++){
								for (int j = 0; j < n2; j++){
									tile_in[i*n2 + j] = 0.0;
									// convolve list
									int [] asym_indx =   dct_kernels.asym_indx[color][kernelTileY][kernelTileX];
									double [] asym_val = dct_kernels.asym_val[color][kernelTileY][kernelTileX];
									for (int indx = 0; indx < asym_indx.length; indx++){
										int xy = asym_indx[indx];
										if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
											System.out.println("i="+i+" j="+j+" indx="+indx+" xy="+xy);
										}
										if (xy >= 0) {
											int dy = (xy / dct_kernels.asym_size) - asym_center;
											int dx = (xy % dct_kernels.asym_size) - asym_center;
											int y = tileY*dct_size - dy + i;
											int x = tileX*dct_size - dx + j;
											if (y < 0) y &= 1; 
											if (x < 0) x &= 1;
											if (y >= height) y = (height - 2) + (y & 1);
											if (x >= width)  x = (width - 2) +  (x & 1);
											tile_in[i*n2 + j] += asym_val[indx] * dpixels[ y * width + x]; 
											if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
												System.out.println("dy= "+dy+" dx="+dx+" x = "+x+" y="+y+" y*width + x="+(y*width + x));
												System.out.println("asym_val["+indx+"]="+asym_val[indx]+
														"  dpixels["+(y * width + x)+"]="+ dpixels[ y * width + x]+
														"tile_in["+(i*n2 + j)+"]="+tile_in[i*n2 + j]);
											}
										}
									}
								}
							}
							// directly convolve with symmetrical kernel (debug feature
							if ((dct_kernels != null) && convolve_direct){
								double [] dir_sym = dct_kernels.st_direct[color][kernelTileY][kernelTileX];
								double s0 = 0;
								for (int i = 0; i < n2; i++){
									for (int j = 0; j < n2; j++){
										int indx = i*n2+j;
										sym_conv[indx] = 0.0; // dir_sym[0]* tile_in[indx];
										for (int dy = -dct_size +1; dy < dct_size; dy++){
											int ady = (dy>=0)?dy:-dy;
											int sgny = 1;
											int y = i - dy;
											if (y < 0){
												y = -1 -y;
												sgny = -sgny;
											}
											if (y >= n2){
												y = 2*n2 - y -1;
												sgny = -sgny;
											}
											for (int dx = -dct_size +1; dx < dct_size; dx++){
												int adx = (dx >= 0)? dx:-dx;
												int sgn = sgny;
												int x = j - dx;
												if (x < 0){
													x = -1 -x;
													sgn = -sgn;
												}
												if (x >= n2){
													x = 2*n2 - x -1;
													sgn = -sgn;
												}
												sym_conv[indx] += sgn*dir_sym[ady * dct_size + adx] * tile_in[y * n2 + x];
												s0+=dir_sym[ady * dct_size + adx];
												if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2) &&
														(i == dct_size) && (j== dct_size)) {
													System.out.println("i="+i+" j="+j+" dy="+dy+" dx="+dx+" ady="+ady+" adx="+adx+
															" y="+y+" x="+x+" sgny="+sgny+" sgn="+sgn+
															"sym_conv["+indx+"] += "+sgn+"* dir_sym["+(ady * dct_size + adx)+"] * tile_in["+(y * n2 + x)+"] +="+
															sgn+"* "+ dir_sym[ady * dct_size + adx]+" * "+tile_in[y * n2 + x]+" +="+
															(sgn*dir_sym[ady * dct_size + adx] * tile_in[y * n2 + x])+" ="+sym_conv[indx]);
												}

											}
										}
									}
								}


								if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
									//								if ((tileY == debug_tileY) && (tileX == debug_tileX)) {
									double [][] pair = {tile_in, sym_conv};
									sdfa_instance.showArrays(pair,  n2, n2, true, "dconv-X"+tileX+"Y"+tileY+"C"+color);
									sdfa_instance.showArrays(dir_sym,  dct_size, dct_size, "dk-X"+tileX+"Y"+tileY+"C"+color);
									double s1=0,s2=0;
									for (int i = 0; i<tile_in.length; i++){
										s1 +=tile_in[i];
										s2 +=sym_conv[i];
									}
									double s3 = 0.0;
									for (int i=0; i<dct_size;i++){
										for (int j=0; j<dct_size;j++){
											double d = dir_sym[i*dct_size+j];
											if (i > 0) d*=2;
											if (j > 0) d*=2;
											s3+=d;
										}
									}
									System.out.println("s1="+s1+" s2="+s2+" s1/s2="+(s1/s2)+" s0="+s0+" s3="+s3);
								}								
//								tile_in = sym_conv.clone(); 
								System.arraycopy(sym_conv, 0, tile_in, 0, n2*n2);
							}
						} else { // no aberration correction, just copy data
							for (int i = 0; i < n2;i++){
								System.arraycopy(dpixels, (tileY*width+tileX)*dct_size + i*width, tile_in, i*n2, n2);
							}
						}
						tile_folded=dtt.fold_tile(tile_in, dct_size, 0); // DCCT
						tile_out=dtt.dttt_iv  (tile_folded, dct_mode, dct_size);
						if ((dct_kernels != null) && !skip_sym){ // convolve in frequency domain with sym_kernel
							double s0 =0;

							if (debug_mode == 2){
								for (int i=0;i<dct_kernels.st_kernels[color][kernelTileY][kernelTileX].length; i++){
									s0+=dct_kernels.st_kernels[color][kernelTileY][kernelTileX][i];
								}
								s0 = dct_size*dct_size/s0;
							} else if (debug_mode == 3){
								for (int i=0;i<dct_size;i++){
									double scale0 = (i>0)?2.0:1.0; 
									for (int j=0;j<dct_size;j++){
										double scale = scale0*((j>0)?2.0:1.0);
										int indx = i*dct_size+j;
										s0+=scale*dct_kernels.st_kernels[color][kernelTileY][kernelTileX][indx];
									}
								}
								s0 = (2*dct_size-1)*(2*dct_size-1)/s0;
							}else if (debug_mode == 4){
								//dciii								
								for (int i=0;i<dct_kernels.st_kernels[color][kernelTileY][kernelTileX].length; i++){
									s0+=dciii[i]* dct_kernels.st_kernels[color][kernelTileY][kernelTileX][i];
								}
								s0 = dct_size*dct_size/s0;
							} else s0 = 1.0;
							
							for (int i = 0; i < tile_out.length; i++){
								tile_out[i] *= s0;
							}
						}
						
						if ((tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
							tile_out_copy = tile_out.clone();
						}
						
						
						if ((dct_kernels != null) && !skip_sym){ // convolve in frequency domain with sym_kernel
							for (int i = 0; i < tile_out.length; i++){
								tile_out[i] *=dct_kernels.st_kernels[color][kernelTileY][kernelTileX][i];
							}
						}						

						
						if ((dct_kernels!=null) && (tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
							double [][] dbg_tile = {
									dct_kernels.st_direct[color][kernelTileY][kernelTileX],
									dct_kernels.st_kernels[color][kernelTileY][kernelTileX],
									tile_out_copy,
									tile_out};
							if (globalDebugLevel > 0){
								sdfa_instance.showArrays(tile_in,  n2, n2, "tile_in-X"+tileX+"Y"+tileY+"C"+color);
								sdfa_instance.showArrays(dbg_tile,  dct_size, dct_size, true, "dbg-X"+tileX+"Y"+tileY+"C"+color);
								System.out.println("tileY="+tileY+" tileX="+tileX+" kernelTileY="+kernelTileY+" kernelTileX="+kernelTileX);
								double s0=0.0, s1=0.0, s2=0.0, s3=0.0;
								for (int i=0;i<dct_size;i++){
									double scale0 = (i>0)?2.0:1.0; 
									for (int j=0;j<dct_size;j++){
										double scale = scale0*((j>0)?2.0:1.0);
										int indx = i*dct_size+j;
										s0+=scale*dct_kernels.st_direct[color][kernelTileY][kernelTileX][indx];
										s1+=scale*dct_kernels.st_kernels[color][kernelTileY][kernelTileX][indx];
										s2+=      dct_kernels.st_kernels[color][kernelTileY][kernelTileX][indx];
										s3+=dciii[indx]*dct_kernels.st_kernels[color][kernelTileY][kernelTileX][indx];
									}
								}
								System.out.println("s0="+s0+" s1="+s1+" s2="+s2+" s3="+s3);
							}
						}
						System.arraycopy(tile_out, 0, dct_data[tileY][tileX], 0, tile_out.length);
					}
				}
			};
		}		      
		startAndJoin(threads);
		return dct_data;
	}
	
	// extract DCT transformed parameters in linescan order (for visualization)
	public double [] lapped_dct_dbg(
			final double [][][] dct_data,
			final int           threadsMax,     // maximal number of threads to launch                         
			final int           globalDebugLevel)
	{
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(dct_data[0][0].length));
		final int dct_len = dct_size*dct_size;
		final double [] dct_data_out = new double[tilesY*tilesX*dct_len];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int i=0; i<dct_data_out.length;i++) dct_data_out[i]= 0;

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						for (int i = 0; i < dct_size;i++){
							System.arraycopy(dct_data[tileY][tileX], dct_size* i, dct_data_out, ((tileY*dct_size + i) *tilesX + tileX)*dct_size , dct_size);
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return dct_data_out;
	}
	
	public void dct_lpf(
			final double sigma,
			final double [][][] dct_data,
			final int       threadsMax,     // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(dct_data[0][0].length));
		final int dct_len = dct_size*dct_size;
		final double [] filter_direct= new double[dct_len];
		if (sigma == 0) {
			filter_direct[0] = 1.0; 
			for (int i= 1; i<filter_direct.length;i++) filter_direct[i] =0; 
		} else {
			for (int i = 0; i < dct_size; i++){
				for (int j = 0; j < dct_size; j++){
					filter_direct[i*dct_size+j] = Math.exp(-(i*i+j*j)/(2*sigma));
				}
			}
		}
		// normalize
		double sum = 0;
		for (int i = 0; i < dct_size; i++){
			for (int j = 0; j < dct_size; j++){
				double d = 	filter_direct[i*dct_size+j];
				d*=Math.cos(Math.PI*i/(2*dct_size))*Math.cos(Math.PI*j/(2*dct_size));
				if (i > 0) d*= 2.0;
				if (j > 0) d*= 2.0;
				sum +=d;
			}
		}
		for (int i = 0; i<filter_direct.length; i++){
			filter_direct[i] /= sum;
		}
		
		if (globalDebugLevel > 0) {
			for (int i=0; i<filter_direct.length;i++){
				System.out.println("dct_lpf_psf() "+i+": "+filter_direct[i]); 
			}
		}
		DttRad2 dtt = new DttRad2(dct_size);
		final double [] filter= dtt.dttt_iiie(filter_direct);
		final double [] dbg_filter= dtt.dttt_ii(filter);
		
//		for (int i=0; i < filter.length;i++) filter[i] *= dct_size;  
		for (int i=0; i < filter.length;i++) filter[i] *= 2*dct_size;  
		
		if (globalDebugLevel > 0) {
			for (int i=0; i<filter.length;i++){
				System.out.println("dct_lpf_psf() "+i+": "+filter[i]); 
			}
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			double [][] ff = {filter_direct,filter,dbg_filter};
			sdfa_instance.showArrays(ff,  dct_size,dct_size, true, "filter_lpf");
		}
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						for (int i = 0; i < filter.length; i++){
							dct_data[tileY][tileX][i] *= filter[i];
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
	}
	
	public double [][][][] dct_color_convert(
			final double [][][][] dct_data,
			final double kr,
			final double kb,
			final double sigma_rb,        // blur of channels 0,1 (r,b) in addition to 2 (g)
			final double sigma_y,         // blur of Y from G
			final double sigma_color,     // blur of Pr, Pb in addition to Y
			final int       threadsMax,     // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
		final int tilesY=dct_data[0].length;
		final int tilesX=dct_data[0][0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(dct_data[0][0][0].length));
		final int dct_len = dct_size*dct_size;
		final double [][][][] yPrPb = new double [3][tilesY][tilesX][dct_len];
		final double [][][] filters = new double [3][3][dct_len];
		final double kg = 1.0 - kr - kb;
		final double [][] filters_proto_direct = new double[3][dct_len];
		final double [][] filters_proto = new double[3][];
		System.out.println("dct_color_convert(): kr="+kr+" kg="+kg+" kb="+kb);
		final double [] sigmas = {sigma_rb,sigma_y,sigma_color};
		double [] norm_sym_weights = new double [dct_size*dct_size];
		for (int i = 0; i < dct_size; i++){
			for (int j = 0; j < dct_size; j++){
				double d = 	Math.cos(Math.PI*i/(2*dct_size))*Math.cos(Math.PI*j/(2*dct_size));
				if (i > 0) d*= 2.0;
				if (j > 0) d*= 2.0;
				norm_sym_weights[i*dct_size+j] = d;
			}
		}
		
		for (int n = 0; n<3; n++) {
			
			double s = 0.0;
			for (int i = 0; i < dct_size; i++){
				for (int j = 0; j < dct_size; j++){
					double d;
					if (sigmas[n] == 0.0)   d = ((i == 0) && (j==0))? 1.0:0.0;
					else                    d = Math.exp(-(i*i+j*j)/(2*sigmas[n]));
					filters_proto_direct[n][i*dct_size+j] = d;
				}
				
			}
			for (int i = 0; i< dct_len; i++){
				s += norm_sym_weights[i]*filters_proto_direct[n][i];
			}
			
			if (globalDebugLevel>0) System.out.println("dct_color_convert(): sigmas["+n+"]="+sigmas[n]+", sum="+s);
			for (int i = 0; i < dct_len; i++){
				filters_proto_direct[n][i] /=s;
			}
		}
		
		DttRad2 dtt = new DttRad2(dct_size);
		for (int i = 0; i < filters_proto.length; i++){
			filters_proto[i] = dtt.dttt_iiie(filters_proto_direct[i]);
			if (globalDebugLevel > 0)  System.out.println("filters_proto.length="+filters_proto.length+" filters_proto["+i+"].length="+filters_proto[i].length+" dct_len="+dct_len+" dct_size="+dct_size);
			for (int j=0; j < dct_len; j++) filters_proto[i][j] *= 2*dct_size;  

		}
		if (globalDebugLevel > 0) {
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			double [][] ff = {filters_proto_direct[0],filters_proto_direct[1],filters_proto_direct[2],filters_proto[0],filters_proto[1],filters_proto[2]};
			sdfa_instance.showArrays(ff,  dct_size,dct_size, true, "filters_proto");
		}

		double [][] coeff_arr ={
				{ kr,              kb,               kg             },  // Y = R*Kr+G*Hg+B*Kb
				{ 0.5,            -kb/(2.0*(1-kr)), -kg/(2.0*(1-kr))},  // Pr =  R* 0.5  - G* Kg/(2.0*(1-Kr)) - B *Kb/(2.0*(1-Kr))
				{-kr/(2.0*(1-kb)), 0.5,             -kg/(2.0*(1-kb))}}; // Pb =  B* 0.5  - G* Kg/(2.0*(1-Kb)) - R *Kr/(2.0*(1-Kb))
		for (int k = 0; k < dct_len; k++){
			for (int i = 0; i < coeff_arr.length; i++){
				for (int j = 0; j < coeff_arr.length; j++){
					filters[i][j][k] = coeff_arr[i][j]* filters_proto[1][k];      // minimal blur - for all sigma_y
					if (i > 0){
						filters[i][j][k] *= filters_proto[2][k]; // for Pr, Pb sigma_color
					}
					if (j <2){ // all but green 
						filters[i][j][k] *= filters_proto[0][k]; // for R,B sigma_rb
					}
					
				}
			}
		}
		if (globalDebugLevel > 0) {
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			double [][] ff = {
					filters[0][0], filters[0][1], filters[0][2],
					filters[1][0], filters[1][1], filters[1][2],
					filters[2][0], filters[2][1], filters[2][2]};
			sdfa_instance.showArrays(ff,  dct_size,dct_size, true, "filters");
		}
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						for (int i = 0; i < filters.length; i++){
							for (int k = 0; k <dct_len; k++){
								yPrPb[i][tileY][tileX][k]=0.0;
								for (int j = 0; j < filters[i].length; j++){
									yPrPb[i][tileY][tileX][k] += filters[i][j][k] * dct_data[j][tileY][tileX][k];
								}
							}
							
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return yPrPb;
	}
	

	
	
	
	
	public double [] lapped_idct(
//			final double [][][] dctdc_data,  // array [tilesY][tilesX][dct_size*dct_size+1] - last element is DC value  
			final double [][][] dct_data,  // array [tilesY][tilesX][dct_size*dct_size]  
			final int       dct_size,
			final int       window_type,
			final int       threadsMax,  // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
//		final int tilesX=dct_width/dct_size;
//		final int tilesY=dct_data.length/(dct_width*dct_size);
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;

		final int width=  (tilesX+1)*dct_size;
		final int height= (tilesY+1)*dct_size;
		if (globalDebugLevel > 0) {
			System.out.println("lapped_idct():tilesX=   "+tilesX);
			System.out.println("lapped_idct():tilesY=   "+tilesY);
			System.out.println("lapped_idct():width=    "+width);
			System.out.println("lapped_idct():height=   "+height);
		}
		final double [] dpixels = new double[width*height];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		final AtomicInteger nser = new AtomicInteger(0);
		final int [][][] tiles_list = new int[4][][];
		for (int n=0; n<4; n++){
			int nx = (tilesX + 1 - (n &1)) / 2;
			int ny = (tilesY + 1 - ((n>>1) & 1)) / 2;
			tiles_list[n] = new int [nx*ny][2];
			int indx = 0;
			for (int i = 0;i < ny; i++) for (int j = 0; j < nx; j++){
				tiles_list[n][indx][0]=2*j+(n &1);
				tiles_list[n][indx++][1]=2*i+((n>>1) & 1);
			}
		}
		for (int i=0; i<dpixels.length;i++) dpixels[i]= 0;
		for (int n=0; n<4; n++){
			nser.set(n);
			ai.set(0);
			for (int ithread = 0; ithread < threads.length; ithread++) {
				threads[ithread] = new Thread() {
					public void run() {
						DttRad2 dtt = new DttRad2(dct_size);
						dtt.set_window(window_type);
						double [] tile_in = new double[dct_size * dct_size];
						double [] tile_dct; // = new double[dct_size * dct_size];
						double [] tile_out; //  = new double[4*dct_size * dct_size];
						int tileY,tileX;
						int n2 = dct_size * 2;
						for (int nTile = ai.getAndIncrement(); nTile < tiles_list[nser.get()].length; nTile = ai.getAndIncrement()) {
							tileX = tiles_list[nser.get()][nTile][0];
							tileY = tiles_list[nser.get()][nTile][1];
							System.arraycopy(dct_data[tileY][tileX], 0, tile_in, 0, tile_in.length);
							tile_dct=dtt.dttt_iv  (tile_in, 0, dct_size);
							tile_out=dtt.unfold_tile(tile_dct, dct_size, 0); // mpode=0 - DCCT
							for (int i = 0; i < n2;i++){
								int start_line = ((tileY*dct_size + i) *(tilesX+1) + tileX)*dct_size; 
								for (int j = 0; j<n2;j++) {
									dpixels[start_line + j] += tile_out[n2 * i + j]; //  +1.0; 
								}
							}
						}
					}
				};
			}		      
			startAndJoin(threads);
		}
		return dpixels;
	}

	// perform 2d clt and apply aberration corrections, all colors 
	public double [][][][][] clt_aberrations( 
			final double [][]       image_data,
			final int               width,
			final double [][][][][] clt_kernels, // [color][tileY][tileX][band][pixel] , size should match image (have 1 tile around)
			final int               kernel_step,
			final int               transform_size,
			final int               window_type,
			final double            shiftX, // shift image horizontally (positive - right) - just for testing
			final double            shiftY, // shift image vertically (positive - down)
			final int               debug_tileX,
			final int               debug_tileY,
			final boolean           no_fract_shift,
			final boolean           no_deconvolution,
			final boolean           transpose,
			final int               threadsMax,  // maximal number of threads to launch                         
			final int               globalDebugLevel)
	{
		final int nChn = image_data.length;
		final int height=image_data[0].length/width;
		final int tilesX=width/transform_size;
		final int tilesY=height/transform_size;
		final int nTilesInChn=tilesX*tilesY; 
		final int nTiles=tilesX*tilesY*nChn; 
		final double [][][][][] clt_data = new double[nChn][tilesY][tilesX][4][];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		if (globalDebugLevel > 0) {
			System.out.println("clt_aberrations(): width="+width+" height="+height+" transform_size="+transform_size+
					" debug_tileX="+debug_tileX+" debug_tileY="+debug_tileY+" globalDebugLevel="+globalDebugLevel);
		}
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(transform_size);
					dtt.set_window(window_type);
					int tileY,tileX, chn;
					//						showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
					double centerX; // center of aberration-corrected (common model) tile, X
					double centerY; // 
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						chn=nTile/nTilesInChn;
						tileY =(nTile % nTilesInChn)/tilesX;
						tileX = nTile % tilesX;
//						centerX = tileX * transform_size - transform_size/2 - shiftX;
//						centerY = tileY * transform_size - transform_size/2 - shiftY;
						centerX = tileX * transform_size + transform_size/2 - shiftX;
						centerY = tileY * transform_size + transform_size/2 - shiftY;

						double [] fract_shiftXY = extract_correct_tile( // return a pair of resudual offsets
								image_data,
								width,       // image width
								clt_kernels, // [color][tileY][tileX][band][pixel]
								clt_data[chn][tileY][tileX], //double  [][]        clt_tile,    // should be double [4][];
								kernel_step,
								transform_size,
								dtt, 
								chn,                              
								centerX, // center of aberration-corrected (common model) tile, X
								centerY, //
								(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2), // external tile compare
								no_deconvolution,
								transpose);
						if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)  && (chn == 2)) {
							showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
							String [] titles = {"CC","SC","CS","SS"};
							sdfa_instance.showArrays(clt_data[chn][tileY][tileX],  transform_size, transform_size, true, "pre-shifted_x"+tileX+"_y"+tileY, titles);
						}
						
						if ((globalDebugLevel > -1) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
								(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)) {
							System.out.println("clt_aberrations(): color="+chn+", tileX="+tileX+", tileY="+tileY+
									" fract_shiftXY[0]="+fract_shiftXY[0]+" fract_shiftXY[1]="+fract_shiftXY[1]);
						}
						
						if (!no_fract_shift) {
							// apply residual shift
							fract_shift(    // fractional shift in transform domain. Currently uses sin/cos - change to tables with 2? rotations
									clt_data[chn][tileY][tileX], // double  [][]  clt_tile,
									transform_size,
									fract_shiftXY[0],            // double        shiftX,
									fract_shiftXY[1],            // double        shiftY,
//									(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)); // external tile compare
									((globalDebugLevel > 0) && (chn==0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
											(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)));									
							if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)) {
								showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
								String [] titles = {"CC","SC","CS","SS"};
								sdfa_instance.showArrays(clt_data[chn][tileY][tileX],  transform_size, transform_size, true, "shifted_x"+tileX+"_y"+tileY, titles);
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return clt_data;
	}


	public double [][][][][][] clt_aberrations_quad(
			final double              disparity,
			final double [][][]       image_data, // first index - number of image in a quad
			final int                 width,
			final GeometryCorrection  geometryCorrection,
			final double [][][][][][] clt_kernels, // [channel_in_quad][color][tileY][tileX][band][pixel] , size should match image (have 1 tile around)
			final int                 kernel_step,
			final int                 transform_size,
			final int                 window_type,
			final double              shiftX, // shift image horizontally (positive - right) - just for testing
			final double              shiftY, // shift image vertically (positive - down)
			final int                 debug_tileX,
			final int                 debug_tileY,
			final boolean             no_fract_shift,
			final boolean             no_deconvolution,
			final boolean             transpose,
			final int                 threadsMax,  // maximal number of threads to launch                         
			final int                 globalDebugLevel)
	{
		final int quad = 4;   // number of subcameras
		final int numcol = 3; // number of colors
		final int nChn = image_data[0].length;
		final int height=image_data[0][0].length/width;
		final int tilesX=width/transform_size;
		final int tilesY=height/transform_size;
		final int nTilesInChn=tilesX*tilesY; 
//		final int nTiles=tilesX*tilesY*nChn; 
		final double [][][][][][] clt_data = new double[quad][nChn][tilesY][tilesX][4][];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		
		
		if (globalDebugLevel > 0) {
			System.out.println("clt_aberrations(): width="+width+" height="+height+" transform_size="+transform_size+
					" debug_tileX="+debug_tileX+" debug_tileY="+debug_tileY+" globalDebugLevel="+globalDebugLevel);
		}
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(transform_size);
					dtt.set_window(window_type);
					int tileY,tileX; // , chn;
					//						showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
					double centerX; // center of aberration-corrected (common model) tile, X
					double centerY; //
					double [][] fract_shiftsXY = new double[quad][];

//					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
					for (int nTile = ai.getAndIncrement(); nTile < nTilesInChn; nTile = ai.getAndIncrement()) {
						// TODO: make all color channels to be processed here (atomically)
						//						chn=nTile/nTilesInChn;
						//						tileY =(nTile % nTilesInChn)/tilesX;
						//						tileX = nTile % tilesX;
						tileY = nTile /tilesX;
						tileX = nTile % tilesX;
						for (int chn = 0; chn <numcol; chn++) {


							centerX = tileX * transform_size + transform_size/2 - shiftX;
							centerY = tileY * transform_size + transform_size/2 - shiftY;
							double [][] centersXY = geometryCorrection.getPortsCoordinates(
									centerX,
									centerY,
									disparity);
							if ((globalDebugLevel > 0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
									(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)) {
								for (int i = 0; i < quad; i++) {
									System.out.println("clt_aberrations_quad(): color="+chn+", tileX="+tileX+", tileY="+tileY+
											" centerX="+centerX+" centerY="+centerY+" disparity="+disparity+
											" centersXY["+i+"][0]="+centersXY[i][0]+" centersXY["+i+"][1]="+centersXY[i][1]);
								}
							}

							for (int i = 0; i < quad; i++) {
								fract_shiftsXY[i] = extract_correct_tile( // return a pair of resudual offsets
										image_data[i],
										width,       // image width
										clt_kernels[i], // [color][tileY][tileX][band][pixel]
										clt_data[i][chn][tileY][tileX], //double  [][]        clt_tile,    // should be double [4][];
										kernel_step,
										transform_size,
										dtt, 
										chn,                              
										centersXY[i][0], // centerX, // center of aberration-corrected (common model) tile, X
										centersXY[i][1], // centerY, //
										(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2), // external tile compare
										no_deconvolution,
										transpose);
							}
							if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)  && (chn == 2)) {
								showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
								String [] titles = {"CC0","SC0","CS0","SS0","CC1","SC1","CS1","SS1","CC2","SC2","CS2","SS2","CC3","SC3","CS3","SS3"};
								double [][] dbg_tile = new double [16][];
								for (int i = 0; i < 16; i++) dbg_tile[i]=clt_data[i>>2][chn][tileY][tileX][i & 3];   
								sdfa_instance.showArrays(dbg_tile,  transform_size, transform_size, true, "pre-shifted_x"+tileX+"_y"+tileY, titles);
							}

							if ((globalDebugLevel > 0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
									(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)) {
								for (int i = 0; i < quad; i++) {
									System.out.println("clt_aberrations_quad(): color="+chn+", tileX="+tileX+", tileY="+tileY+
											" fract_shiftsXY["+i+"][0]="+fract_shiftsXY[i][0]+" fract_shiftsXY["+i+"][1]="+fract_shiftsXY[i][1]);
								}
							}

							if (!no_fract_shift) {
								// apply residual shift
								for (int i = 0; i < quad; i++) {
									fract_shift(    // fractional shift in transform domain. Currently uses sin/cos - change to tables with 2? rotations
											clt_data[i][chn][tileY][tileX], // double  [][]  clt_tile,
											transform_size,
											fract_shiftsXY[i][0],            // double        shiftX,
											fract_shiftsXY[i][1],            // double        shiftY,
											//									(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)); // external tile compare
											((globalDebugLevel > 0) && (chn==0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
													(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)));									
								}
								if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)) {
									showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
									String [] titles = {"CC0","SC0","CS0","SS0","CC1","SC1","CS1","SS1","CC2","SC2","CS2","SS2","CC3","SC3","CS3","SS3"};
									double [][] dbg_tile = new double [16][];
									for (int i = 0; i < 16; i++) dbg_tile[i]=clt_data[i>>2][chn][tileY][tileX][i & 3];   
									sdfa_instance.showArrays(dbg_tile,  transform_size, transform_size, true, "shifted_x"+tileX+"_y"+tileY, titles);
								}
							}
						}
						// all color channels are done here
					}
				}
			};
		}		      
		startAndJoin(threads);
		return clt_data;
	}

/*
 * 	
 */
	
	
	public double [][][][][][] clt_aberrations_quad_corr(
			final int [][]            tile_op,         // [tilesY][tilesX] - what to do - 0 - nothing for this tile
//			final double              disparity,
			final double [][]         disparity_array, // [tilesY][tilesX] - individual per-tile expected disparity
			final double [][][]       image_data, // first index - number of image in a quad
			 // correlation results - final and partial          
			final double [][][][]     clt_corr_combo,  // [type][tilesY][tilesX][(2*transform_size-1)*(2*transform_size-1)] // if null - will not calculate
			                                           // [type][tilesY][tilesX] should be set by caller
													   // types: 0 - selected correlation (product+offset), 1 - sum 
			
			final double [][][][][]   clt_corr_partial,// [tilesY][tilesX][quad]color][(2*transform_size-1)*(2*transform_size-1)] // if null - will not calculate
                                                       // [tilesY][tilesX] should be set by caller
			final double [][]         clt_mismatch,    // [12][tilesY * tilesX] // transpose unapplied. null - do not calculate

			final double [][]         disparity_map,   // [8][tilesY][tilesX], only [6][] is needed on input or null - do not calculate
			                                           // last 2 - contrast, avg/ "geometric average)
			final double [][][][]     texture_tiles,   // [tilesY][tilesX]["RGBA".length()][];  null - will skip images combining

			final int                 width,
			final double              corr_fat_zero,    // add to denominator to modify phase correlation (same units as data1, data2). <0 - pure sum
			final boolean             corr_sym,
			final double              corr_offset,
			final double              corr_red,
			final double              corr_blue,
			final double              corr_sigma,
//			final int                 corr_mask,       // which pairs to combine in the combo:  1 - top, 2 bottom, 4 - left, 8 - right
			final boolean             corr_normalize,  // normalize correlation results by rms
	  		final double              min_corr,        // 0.0001; // minimal correlation value to consider valid 
			final double              max_corr_sigma,  // 1.5;  // weights of points around global max to find fractional
			final double              max_corr_radius, // 3.5;
			
			final int                 enhortho_width,  // 2;    // reduce weight of center correlation pixels from center (0 - none, 1 - center, 2 +/-1 from center)
			final double              enhortho_scale,  // 0.2;  // multiply center correlation pixels (inside enhortho_width)
			
			final boolean 			  max_corr_double, //"Double pass when masking center of mass to reduce preference for integer values
			final int                 corr_mode, // Correlation mode: 0 - integer max, 1 - center of mass, 2 - polynomial
			final double              min_shot,        // 10.0;  // Do not adjust for shot noise if lower than
			final double              scale_shot,      // 3.0;   // scale when dividing by sqrt ( <0 - disable correction)
			final double              diff_sigma,      // 5.0;//RMS difference from average to reduce weights (~ 1.0 - 1/255 full scale image)
			final double              diff_threshold,  // 5.0;   // RMS difference from average to discard channel (~ 1.0 - 1/255 full scale image)
			final boolean             diff_gauss,      // true;  // when averaging images, use gaussian around average as weight (false - sharp all/nothing)
			final double              min_agree,       // 3.0;   // minimal number of channels to agree on a point (real number to work with fuzzy averages)
			final boolean             dust_remove,     // Do not reduce average weight when only one image differes much from the average
			final boolean             keep_weights,    // Add port weights to RGBA stack (debug feature)
			final GeometryCorrection  geometryCorrection,
			final double [][][][][][] clt_kernels, // [channel_in_quad][color][tileY][tileX][band][pixel] , size should match image (have 1 tile around)
			final int                 kernel_step,
			final int                 transform_size,
			final int                 window_type,
			final double [][]         shiftXY, // [port]{shiftX,shiftY}
			final double [][][]       fine_corr, // quadratic cofficients for fine correction (or null)
			final double              corr_magic_scale, // stil not understood coefficent that reduces reported disparity value.  Seems to be around 8.5  
			final double              shiftX, // shift image horizontally (positive - right) - just for testing
			final double              shiftY, // shift image vertically (positive - down)
			final int                 debug_tileX,
			final int                 debug_tileY,
			final boolean             no_fract_shift,
			final boolean             no_deconvolution,
			final int                 threadsMax,  // maximal number of threads to launch                         
			final int                 globalDebugLevel)
	{
		final int quad = 4;   // number of subcameras
		final int numcol = 3; // number of colors
		final int nChn = image_data[0].length;
		final int height=image_data[0][0].length/width;
		final int tilesX=width/transform_size;
		final int tilesY=height/transform_size;
		final int nTilesInChn=tilesX*tilesY; 
		final double [][][][][][] clt_data = new double[quad][nChn][tilesY][tilesX][][];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		final double [] col_weights= new double [numcol]; // colors are RBG  
		col_weights[2] = 1.0/(1.0 + corr_red + corr_blue);    // green color 
		col_weights[0] = corr_red *  col_weights[2]; 
		col_weights[1] = corr_blue * col_weights[2]; 
		final int corr_size = transform_size * 2 -1;
		final int [][] transpose_indices = new int [corr_size*(corr_size-1)/2][2];
		int indx = 0;
		for (int i =0; i < corr_size-1; i++){
			for (int j = i+1; j < corr_size; j++){
				transpose_indices[indx  ][0] = i * corr_size + j;
				transpose_indices[indx++][1] = j * corr_size + i;
			}
		}
		
		// reducing weight of on-axis correlation values to enhance detection of vertical/horizontal lines
		// multiply correlation results inside the horizontal center strip  2*enhortho_width - 1 wide by enhortho_scale
		
		final double [] enh_ortho_scale = new double [corr_size];
		for (int i = 0; i < corr_size; i++){
			if ((i < (transform_size - enhortho_width)) || (i > (transform_size - 2 + enhortho_width))) enh_ortho_scale[i] = 1.0;
			else enh_ortho_scale[i] = enhortho_scale;
			if (i == (transform_size-1)) enh_ortho_scale[i] = 0.0 ; // hardwired 0 in the center
			enh_ortho_scale[i] *= Math.sin(Math.PI*(i+1.0)/(2*transform_size));
		}
		if (globalDebugLevel > 0){
			System.out.println("enhortho_width="+ enhortho_width+" enhortho_scale="+ enhortho_scale);
			for (int i = 0; i < corr_size; i++){
				System.out.println(" enh_ortho_scale["+i+"]="+ enh_ortho_scale[i]);
				
			}
		}
		if (globalDebugLevel > 0) {
			System.out.println("clt_aberrations_quad_corr(): width="+width+" height="+height+" transform_size="+transform_size+
					" debug_tileX="+debug_tileX+" debug_tileY="+debug_tileY+" globalDebugLevel="+globalDebugLevel);
		}
		final int [][] zi = 
			{{ 0,  1,  2,  3},
			 {-1,  0, -3,  2},
			 {-2, -3,  0,  1},
			 { 3, -2, -1,  0}};
		final int [][] corr_pairs ={ // {first, second, rot} rot: 0 - as is, 1 - swap y,x
				{0,1,0},
				{2,3,0},
				{0,2,1},
				{1,3,1}};
		
		final double[][] port_offsets = {
				{-0.5, -0.5},
				{ 0.5, -0.5},
				{-0.5,  0.5},
				{ 0.5,  0.5}};
		final int transform_len = transform_size * transform_size;
		
		
		
		final double [] filter_direct= new double[transform_len];
		if (corr_sigma == 0) {
			filter_direct[0] = 1.0; 
			for (int i= 1; i<filter_direct.length;i++) filter_direct[i] =0; 
		} else {
			for (int i = 0; i < transform_size; i++){
				for (int j = 0; j < transform_size; j++){
					filter_direct[i*transform_size+j] = Math.exp(-(i*i+j*j)/(2*corr_sigma)); // FIXME: should be sigma*sigma !
				}
			}
		}
		// normalize
		double sum = 0;
		for (int i = 0; i < transform_size; i++){
			for (int j = 0; j < transform_size; j++){
				double d = 	filter_direct[i*transform_size+j];
				d*=Math.cos(Math.PI*i/(2*transform_size))*Math.cos(Math.PI*j/(2*transform_size));
				if (i > 0) d*= 2.0;
				if (j > 0) d*= 2.0;
				sum +=d;
			}
		}
		for (int i = 0; i<filter_direct.length; i++){
			filter_direct[i] /= sum;
		}
		
		DttRad2 dtt = new DttRad2(transform_size);
		final double [] filter= dtt.dttt_iiie(filter_direct);
		for (int i=0; i < filter.length;i++) filter[i] *= 2*transform_size;  

		// prepare disparity maps and weights
		final int max_search_radius = (int) Math.abs(max_corr_radius); // use negative max_corr_radius for squares instead of circles?
		final int max_search_radius_poly = 1;
		if (globalDebugLevel > 0){
			System.out.println("max_corr_radius=       "+max_corr_radius);
			System.out.println("max_search_radius=     "+max_search_radius);
			System.out.println("max_search_radius_poly="+max_search_radius_poly);
			System.out.println("corr_fat_zero=         "+corr_fat_zero);
			System.out.println("disparity_array[0][0]= "+disparity_array[0][0]);
			
			
		}
		if (disparity_map != null){
			for (int i = 0; i<disparity_map.length;i++){
				disparity_map[i] = new double [tilesY*tilesX];
			}
		}
		if (clt_mismatch != null){
			for (int i = 0; i<clt_mismatch.length;i++){
				clt_mismatch[i] = new double [tilesY*tilesX]; // will use only "center of mass" centers
			}
		}
//		final double [] corr_max_weights =(((max_corr_sigma > 0) && (disparity_map != null))?
//				setMaxXYWeights(max_corr_sigma,max_search_radius): null); // here use square anyway
		final double [] corr_max_weights_poly =(((max_corr_sigma > 0) && (disparity_map != null))?
				setMaxXYWeights(max_corr_sigma,max_search_radius_poly): null); // here use square anyway

		dtt.set_window(window_type);
		final double [] lt_window = dtt.getWin2d();	// [256]
		final double [] lt_window2 = new double [lt_window.length]; // squared
		for (int i = 0; i < lt_window.length; i++) lt_window2[i] = lt_window[i] * lt_window[i];
		
		
		if (globalDebugLevel > 1) {
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			sdfa_instance.showArrays(lt_window,  2*transform_size, 2*transform_size, "lt_window");
		}

		
						
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(transform_size);
					dtt.set_window(window_type);
					int tileY,tileX,tIndex; // , chn;
					//						showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
					double centerX; // center of aberration-corrected (common model) tile, X
					double centerY; //
					double [][] fract_shiftsXY = new double[quad][];
					double [][]     tcorr_combo =    null; // [15*15] pixel space
					double [][][]   tcorr_partial =  null; // [quad][numcol+1][15*15]
					double [][][][] tcorr_tpartial = null; // [quad][numcol+1][4][8*8]
					PolynomialApproximation pa =     null;
					if (corr_max_weights_poly !=null)   pa = new PolynomialApproximation(0); // debug level
					for (int nTile = ai.getAndIncrement(); nTile < nTilesInChn; nTile = ai.getAndIncrement()) {
						tileY = nTile /tilesX;
						tileX = nTile % tilesX;
						tIndex = tileY * tilesX + tileX; 
						if (tile_op[tileY][tileX] == 0) continue; // nothing to do for this tile
						int                 img_mask = getImgMask(tile_op[tileY][tileX]);         // which images to use 
						int                 corr_mask = getPairMask(tile_op[tileY][tileX]);       // which pairs to combine in the combo:  1 - top, 2 bottom, 4 - left, 8 - right
						// mask out pairs that use missing channels
						for (int i = 0; i< corr_pairs.length; i++){
							if ((((1 << corr_pairs[i][0]) & img_mask) == 0) || (((1 << corr_pairs[i][1]) & img_mask) == 0)) {
								corr_mask &= ~ (1 << i);
							}
						}
						boolean debugTile =(tileX == debug_tileX) && (tileY == debug_tileY);
						for (int chn = 0; chn <numcol; chn++) {
							centerX = tileX * transform_size + transform_size/2 - shiftX;
							centerY = tileY * transform_size + transform_size/2 - shiftY;
							double [][] centersXY = geometryCorrection.getPortsCoordinates(
									centerX,
									centerY,
									disparity_array[tileY][tileX]);
							if ((globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)) {
								for (int i = 0; i < quad; i++) {
									System.out.println("clt_aberrations_quad_corr(): color="+chn+", tileX="+tileX+", tileY="+tileY+
											" centerX="+centerX+" centerY="+centerY+" disparity="+disparity_array[tileY][tileX]+
											" centersXY["+i+"][0]="+centersXY[i][0]+" centersXY["+i+"][1]="+centersXY[i][1]);
								}
							}

							if ((globalDebugLevel > -1) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2)) { // before correction
								System.out.print(disparity_array[tileY][tileX]+"\t"+
							    centersXY[0][0]+"\t"+centersXY[0][1]+"\t"+
							    centersXY[1][0]+"\t"+centersXY[1][1]+"\t"+
							    centersXY[2][0]+"\t"+centersXY[2][1]+"\t"+
							    centersXY[3][0]+"\t"+centersXY[3][1]+"\t");
							}

							for (int ip = 0; ip < centersXY.length; ip++){
								centersXY[ip][0] -= shiftXY[ip][0];
								centersXY[ip][1] -= shiftXY[ip][1];
							}
							
							if (fine_corr != null){
								double tX = (2.0 * tileX)/tilesX - 1.0; // -1.0 to +1.0
								double tY = (2.0 * tileY)/tilesY - 1.0; // -1.0 to +1.0
								for (int ip = 0; ip < centersXY.length; ip++){
									//f(x,y)=A*x^2+B*y^2+C*x*y+D*x+E*y+F
									for (int d = 0; d <2; d++)
									centersXY[ip][d] -= (
											fine_corr[ip][d][0]*tX*tX+
											fine_corr[ip][d][1]*tY*tY+
											fine_corr[ip][d][2]*tX*tY+
											fine_corr[ip][d][3]*tX+
											fine_corr[ip][d][4]*tY+
											fine_corr[ip][d][5]);
								}
							}

							if ((globalDebugLevel > -1) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2)) {
								System.out.print(disparity_array[tileY][tileX]+"\t"+
							    centersXY[0][0]+"\t"+centersXY[0][1]+"\t"+
							    centersXY[1][0]+"\t"+centersXY[1][1]+"\t"+
							    centersXY[2][0]+"\t"+centersXY[2][1]+"\t"+
							    centersXY[3][0]+"\t"+centersXY[3][1]+"\t");
							}

							for (int i = 0; i < quad; i++) {
								clt_data[i][chn][tileY][tileX] = new double [4][];
								fract_shiftsXY[i] = extract_correct_tile( // return a pair of resudual offsets
										image_data[i],
										width,       // image width
										clt_kernels[i], // [color][tileY][tileX][band][pixel]
										clt_data[i][chn][tileY][tileX], //double  [][]        clt_tile,    // should be double [4][];
										kernel_step,
										transform_size,
										dtt, 
										chn,                              
										centersXY[i][0], // centerX, // center of aberration-corrected (common model) tile, X
										centersXY[i][1], // centerY, //
										((globalDebugLevel > -1) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2)),
//										(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2), // external tile compare
										no_deconvolution,
										false); // transpose);
							}
							if ((globalDebugLevel > -1) && (tileX == debug_tileX) && (tileY == debug_tileY) && (chn == 2)) {
								System.out.println();
							}							
							if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)  && (chn == 2)) {
								showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
								String [] titles = {"CC0","SC0","CS0","SS0","CC1","SC1","CS1","SS1","CC2","SC2","CS2","SS2","CC3","SC3","CS3","SS3"};
								double [][] dbg_tile = new double [16][];
								for (int i = 0; i < 16; i++) dbg_tile[i]=clt_data[i>>2][chn][tileY][tileX][i & 3];   
								sdfa_instance.showArrays(dbg_tile,  transform_size, transform_size, true, "pre-shifted_x"+tileX+"_y"+tileY, titles);
							}

							if ((globalDebugLevel > 0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
									(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)) {
								for (int i = 0; i < quad; i++) {
									System.out.println("clt_aberrations_quad(): color="+chn+", tileX="+tileX+", tileY="+tileY+
											" fract_shiftsXY["+i+"][0]="+fract_shiftsXY[i][0]+" fract_shiftsXY["+i+"][1]="+fract_shiftsXY[i][1]);
								}
							}

							if (!no_fract_shift) {
								// apply residual shift
								for (int i = 0; i < quad; i++) {
									fract_shift(    // fractional shift in transform domain. Currently uses sin/cos - change to tables with 2? rotations
											clt_data[i][chn][tileY][tileX], // double  [][]  clt_tile,
											transform_size,
											fract_shiftsXY[i][0],            // double        shiftX,
											fract_shiftsXY[i][1],            // double        shiftY,
											//									(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)); // external tile compare
											((globalDebugLevel > 0) && (chn==0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
													(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)));									
								}
								if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)) {
									showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
									String [] titles = {"CC0","SC0","CS0","SS0","CC1","SC1","CS1","SS1","CC2","SC2","CS2","SS2","CC3","SC3","CS3","SS3"};
									double [][] dbg_tile = new double [16][];
									for (int i = 0; i < 16; i++) dbg_tile[i]=clt_data[i>>2][chn][tileY][tileX][i & 3];   
									sdfa_instance.showArrays(dbg_tile,  transform_size, transform_size, true, "shifted_x"+tileX+"_y"+tileY, titles);
								}
							}
						}
						// all color channels are done here
						double extra_disparity = 0.0; // if allowed, shift images extra before trying to combine
						if (clt_corr_combo != null){ // not null - calculate correlations
							
							tcorr_tpartial=new double[corr_pairs.length][numcol+1][4][transform_len];
//							tcorr_tcombo =   new double[quad][transform_len];
							tcorr_partial =  new double[quad][numcol+1][];
							
							for (int pair = 0; pair < corr_pairs.length; pair++){
								for (int chn = 0; chn <numcol; chn++){
									double [][] data1 = clt_data[corr_pairs[pair][0]][chn][tileY][tileX];
									double [][] data2 = clt_data[corr_pairs[pair][1]][chn][tileY][tileX];
									for (int i = 0; i < transform_len; i++) {
										double s1 = 0.0, s2=0.0;
										for (int n = 0; n< 4; n++){
											s1+=data1[n][i] * data1[n][i];
											s2+=data2[n][i] * data2[n][i];
										}
										double scale = 1.0 / (Math.sqrt(s1*s2) + corr_fat_zero*corr_fat_zero); // squared to match units
										for (int n = 0; n<4; n++){
											tcorr_tpartial[pair][chn][n][i] = 0;
											for (int k=0; k<4; k++){
												if (zi[n][k] < 0)
													tcorr_tpartial[pair][chn][n][i] -= 
															data1[-zi[n][k]][i] * data2[k][i];
												else
													tcorr_tpartial[pair][chn][n][i] += 
													data1[zi[n][k]][i] * data2[k][i];
											}
											tcorr_tpartial[pair][chn][n][i] *= scale;
										}
									}
									// got transform-domain correlation for the pair, 1 color
								}
								// calculate composite color 
								for (int i = 0; i < transform_len; i++) {
									for (int n = 0; n<4; n++) {
										tcorr_tpartial[pair][numcol][n][i] = 
												col_weights[0]* tcorr_tpartial[pair][0][n][i] +
												col_weights[1]* tcorr_tpartial[pair][1][n][i] +
												col_weights[2]* tcorr_tpartial[pair][2][n][i];
									}
								}
								// now lpf (only last/composite color if do not preserve intermediate
								int firstColor = (clt_corr_partial == null)? numcol : 0;
								if (corr_sigma >0) {
									for (int chn = firstColor; chn <= numcol; chn++){
										for (int i = 0; i < transform_len; i++) {
											for (int n = 0; n<4; n++) {
												tcorr_tpartial[pair][chn][n][i] *= filter[i];
											}
										}
									}
								}
								// convert to pixel domain - all or just composite color
								for (int chn = firstColor; chn <= numcol; chn++){
									for (int quadrant = 0; quadrant < 4; quadrant++){
										int mode = ((quadrant << 1) & 2) | ((quadrant >> 1) & 1); // transpose
										tcorr_tpartial[pair][chn][quadrant] =
												dtt.dttt_iie(tcorr_tpartial[pair][chn][quadrant], mode, transform_size);
									}
								}
								// convert from 4 quadrants to 15x15 centered tiles (each color or only composite)
								for (int chn = firstColor; chn <= numcol; chn++){
									tcorr_partial[pair][chn] = corr_unfold_tile(
											tcorr_tpartial[pair][chn],
											transform_size);
								}
								// transpose vertical pairs
								if (corr_pairs[pair][2] != 0) {
									for (int chn = firstColor; chn <= numcol; chn++){
										for (int i = 0; i < transpose_indices.length; i++) {
											double d = tcorr_partial[pair][chn][transpose_indices[i][0]];
											tcorr_partial[pair][chn][transpose_indices[i][0]] = tcorr_partial[pair][chn][transpose_indices[i][1]];
											tcorr_partial[pair][chn][transpose_indices[i][1]] = d;
											//transpose_indices									
										}		
									}									
								}
								// make symmetrical around the disparity direction (horizontal) (here using just average, not mul/sum mixture)
								if (corr_sym && (clt_mismatch == null)){ // when measuring clt_mismatch symmetry should be off ! 
									for (int chn = firstColor; chn <= numcol; chn++){
										for (int i = 1 ; i < transform_size; i++){
											int indx1 = (transform_size - 1 - i) * corr_size;
											int indx2 = (transform_size - 1 + i) * corr_size;
											for (int j = 0; j< corr_size; j++){
												int indx1j = indx1 + j;
												int indx2j = indx2 + j;
												tcorr_partial[pair][chn][indx1j] = 
														0.5* (tcorr_partial[pair][chn][indx1j] + tcorr_partial[pair][chn][indx2j]);
												tcorr_partial[pair][chn][indx2j] = tcorr_partial[pair][chn][indx1j];
											}
										}
									}									
								}
							} // all pairs calculated
							tcorr_combo = new double [TCORR_TITLES.length][corr_size * corr_size];
							
							int numPairs = 	0, numPairsHor = 0, numPairsVert = 0;
							for (int pair = 0; pair < corr_pairs.length; pair++) if (((corr_mask >> pair) & 1) != 0){
								numPairs++;
								if (corr_pairs[pair][2] == 0) { // horizontal pair)
									numPairsHor++;
								} else {
									numPairsVert++;
								}
							}
							double avScale = 0.0, avScaleHor = 0.0, avScaleVert = 0.0;
							if (numPairs > 0) {
								boolean debugMax = (globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY);
								avScale = 1.0/numPairs;
								if (numPairsHor > 0)  avScaleHor = 1.0/numPairsHor;
								if (numPairsVert > 0) avScaleVert = 1.0/numPairsVert;
								if (debugMax) {
									System.out.println("avScale = "+avScale+", avScaleHor = "+avScaleHor+", avScaleVert = "+avScaleVert+", corr_offset = "+corr_offset);
								}
								if (corr_offset < 0) { // just add all partial correlations for composite color
									for (int i = 0; i < tcorr_combo[TCORR_COMBO_RSLT].length; i++){
										tcorr_combo[TCORR_COMBO_RSLT][i] = 0.0;
										tcorr_combo[TCORR_COMBO_HOR][i] = 0.0;
										tcorr_combo[TCORR_COMBO_VERT][i] = 0.0;
										for (int pair = 0; pair < corr_pairs.length; pair++) if (((corr_mask >> pair) & 1) != 0){
											tcorr_combo[TCORR_COMBO_RSLT][i] += avScale*tcorr_partial[pair][numcol][i]; // only composite color channel
											if (corr_pairs[pair][2] == 0) { // horizontal pair
												tcorr_combo[TCORR_COMBO_HOR][i] +=  avScaleHor*tcorr_partial[pair][numcol][i]; // only composite color channel
											} else { //vertical pair
												tcorr_combo[TCORR_COMBO_VERT][i] += avScaleVert*tcorr_partial[pair][numcol][i]; // only composite color channel
											}
											if (debugMax) {
												System.out.println("tcorr_combo[TCORR_COMBO_RSLT]["+i+"]="+tcorr_combo[TCORR_COMBO_RSLT][i]+" tcorr_partial["+pair+"]["+numcol+"]["+i+"]="+tcorr_partial[pair][numcol][i]);
											}
										}
//										tcorr_combo[TCORR_COMBO_HOR][i] *=2;   // no have the same scale as tcorr_combo[TCORR_COMBO_RSLT]
//										tcorr_combo[TCORR_COMBO_VERT][i] *=2;
									}
								} else {
									for (int i = 0; i < tcorr_combo[TCORR_COMBO_RSLT].length; i++){
										tcorr_combo[TCORR_COMBO_RSLT][i] = 1.0;
										tcorr_combo[TCORR_COMBO_HOR][i] =  1.0;
										tcorr_combo[TCORR_COMBO_VERT][i] = 1.0;
										for (int pair = 0; pair < corr_pairs.length; pair++) if (((corr_mask >> pair) & 1) != 0){
											tcorr_combo[TCORR_COMBO_RSLT][i] *= (tcorr_partial[pair][numcol][i] + corr_offset); // only composite color channel
											if (corr_pairs[pair][2] == 0) { // horizontal pair
												tcorr_combo[TCORR_COMBO_HOR][i] *= (tcorr_partial[pair][numcol][i] + corr_offset); // only composite color channel
											} else { //vertical pair
												tcorr_combo[TCORR_COMBO_VERT][i] *= (tcorr_partial[pair][numcol][i] + corr_offset); // only composite color channel
											}
											if (debugMax) {
												System.out.println("tcorr_combo[TCORR_COMBO_RSLT]["+i+"]="+tcorr_combo[TCORR_COMBO_RSLT][i]+" tcorr_partial["+pair+"]["+numcol+"]["+i+"]="+tcorr_partial[pair][numcol][i]);
											}
										}
//										tcorr_combo[TCORR_COMBO_HOR][i] *= tcorr_combo[TCORR_COMBO_HOR][i];   // no have the same scale as tcorr_combo[TCORR_COMBO_RSLT]
//										tcorr_combo[TCORR_COMBO_VERT][i] *= tcorr_combo[TCORR_COMBO_VERT][i];
										if (corr_normalize) {
											if (tcorr_combo[TCORR_COMBO_RSLT][i] > 0.0){
												tcorr_combo[TCORR_COMBO_RSLT][i] = Math.pow(tcorr_combo[TCORR_COMBO_RSLT][i],avScale) - corr_offset;
											} else {
												tcorr_combo[TCORR_COMBO_RSLT][i] =  -corr_offset;
											}
											
											if (tcorr_combo[TCORR_COMBO_HOR][i] > 0.0){
												tcorr_combo[TCORR_COMBO_HOR][i] = Math.pow(tcorr_combo[TCORR_COMBO_HOR][i],avScaleHor) - corr_offset;
											} else {
												tcorr_combo[TCORR_COMBO_HOR][i] =  -corr_offset;
											}

											if (tcorr_combo[TCORR_COMBO_VERT][i] > 0.0){
												tcorr_combo[TCORR_COMBO_VERT][i] = Math.pow(tcorr_combo[TCORR_COMBO_VERT][i],avScaleVert) - corr_offset;
											} else {
												tcorr_combo[TCORR_COMBO_VERT][i] =  -corr_offset;
											}
										}
									}
								}
								// calculate sum also
								for (int i = 0; i < tcorr_combo[TCORR_COMBO_SUM].length; i++){
									tcorr_combo[TCORR_COMBO_SUM][i] = 0.0;
									for (int pair = 0; pair < corr_pairs.length; pair++) if (((corr_mask >> pair) & 1) != 0){
										tcorr_combo[TCORR_COMBO_SUM][i] += avScale*tcorr_partial[pair][numcol][i]; // only composite color channel
										if (debugMax) {
											System.out.println("tcorr_combo[TCORR_COMBO_SUM]["+i+"]="+tcorr_combo[TCORR_COMBO_SUM][i]+" tcorr_partial["+pair+"]["+numcol+"]["+i+"]="+tcorr_partial[pair][numcol][i]);
										}
									}
								}
/*								
								double [] rms = new double [tcorr_combo.length];
								for (int n = 0; n < rms.length; n++) rms[n] = 1.0; 
								if (corr_normalize){ // normalize both composite and sum by their RMS
									for (int n = 0; n<tcorr_combo.length; n++){
										rms[n] = 0;
										for (int i = 0; i < tcorr_combo[n].length; i++){
											rms[n] += tcorr_combo[n][i] * tcorr_combo[n][i];
										}
										rms[n] = Math.sqrt(rms[n]/tcorr_combo[n].length);
										if (rms[n] > 0){
											double k = 1.0/rms[n];
											for (int i = 0; i < tcorr_combo[n].length; i++){
												tcorr_combo[n][i] *= k;
											}
										}
									}
								}
*/								
								// return results
								for (int n = 0; n < clt_corr_combo.length; n++){ // tcorr_combo now may be longer than clt_corr_combo
									clt_corr_combo[n][tileY][tileX] = tcorr_combo[n];
								}
								if (clt_corr_partial != null){
									clt_corr_partial[tileY][tileX] = tcorr_partial;
								}
								if (disparity_map != null) {
									int [] icorr_max =getMaxXYInt( // find integer pair or null if below threshold
											tcorr_combo[TCORR_COMBO_RSLT],      // [data_size * data_size]
											corr_size,      
											min_corr,    // minimal value to consider (at integer location, not interpolated)
											debugMax);
									int max_index = -1;
									if (icorr_max == null){
										disparity_map[DISPARITY_INDEX_INT]          [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_INT+1]        [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_CM]           [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_CM+1]         [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_HOR]          [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_HOR_STRENGTH][tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_VERT]         [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_VERT_STRENGTH][tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_POLY]         [tIndex] = Double.NaN;
										disparity_map[DISPARITY_INDEX_POLY+1]       [tIndex] = Double.NaN;
										if (clt_mismatch != null){
											for (int pair = 0; pair < corr_pairs.length; pair++) if (((corr_mask >> pair) & 1) != 0){
												clt_mismatch[3*pair + 0 ][tIndex] = Double.NaN;
												clt_mismatch[3*pair + 1 ][tIndex] = Double.NaN;
												clt_mismatch[3*pair + 2 ][tIndex] = Double.NaN;
											}
										}
									} else {	
										double [] corr_max_XYi = {icorr_max[0],icorr_max[1]};
										disparity_map[DISPARITY_INDEX_INT][tIndex] =  transform_size - 1 -corr_max_XYi[0];
										disparity_map[DISPARITY_INDEX_INT+1][tIndex] = transform_size - 1 -corr_max_XYi[1];
										// for the integer maximum provide contrast and variety
										max_index = icorr_max[1]*corr_size + icorr_max[0];
										disparity_map[DISPARITY_STRENGTH_INDEX][tIndex] = tcorr_combo[TCORR_COMBO_RSLT][max_index]; // correlation combo value at the integer maximum
										// undo scaling caused by optional normalization
//										disparity_map[DISPARITY_VARIATIONS_INDEX][tIndex] = (rms[1]*tcorr_combo[1][max_index])/(rms[0]*tcorr_combo[0][max_index]); // correlation combo value at the integer maximum
										disparity_map[DISPARITY_VARIATIONS_INDEX][tIndex] = (tcorr_combo[TCORR_COMBO_SUM][max_index])/(tcorr_combo[TCORR_COMBO_RSLT][max_index]); // correlation combo value at the integer maximum
										//									Calculate "center of mass" coordinates
										double [] corr_max_XYm = getMaxXYCm( // get fractiona center as a "center of mass" inside circle/square from the integer max
												tcorr_combo[TCORR_COMBO_RSLT],      // [data_size * data_size]
												corr_size,      
												icorr_max, // integer center coordinates (relative to top left)
												max_corr_radius,  // positive - within that distance, negative - within 2*(-radius)+1 square
												max_corr_double, //"Double pass when masking center of mass to reduce preference for integer values
												debugMax);
										disparity_map[DISPARITY_INDEX_CM][tIndex] = transform_size - 1 -corr_max_XYm[0];
										disparity_map[DISPARITY_INDEX_CM+1][tIndex] = transform_size - 1 -corr_max_XYm[1];
										// returns x and strength, not x,y
										double [] corr_max_XS_hor = getMaxXSOrtho( // get fractiona center as a "center of mass" inside circle/square from the integer max
												tcorr_combo[TCORR_COMBO_HOR],      // [data_size * data_size]
												enh_ortho_scale, // [data_size]
												corr_size, 
												max_corr_radius,
//												max_corr_double, // reusing, true - just poly for maximum
												(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)); // debugMax);
										disparity_map[DISPARITY_INDEX_HOR][tIndex] = transform_size - 1 - corr_max_XS_hor[0];
										disparity_map[DISPARITY_INDEX_HOR_STRENGTH][tIndex] = corr_max_XS_hor[1];
										double [] corr_max_XS_vert = getMaxXSOrtho( // get fractiona center as a "center of mass" inside circle/square from the integer max
												tcorr_combo[TCORR_COMBO_VERT],      // [data_size * data_size]
												enh_ortho_scale, // [data_size]
												corr_size, 
												max_corr_radius,
//												max_corr_double, // reusing, true - just poly for maximum (probably keep it that way)
												(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)); // debugMax);
										disparity_map[DISPARITY_INDEX_VERT][tIndex] = transform_size - 1 - corr_max_XS_vert[0];
										disparity_map[DISPARITY_INDEX_VERT_STRENGTH][tIndex] = corr_max_XS_vert[1];
										//									Calculate polynomial interpolated maximum coordinates
										double [] corr_max_XY = getMaxXYPoly( // get interpolated maximum coordinates using 2-nd degree polynomial
												pa,
												tcorr_combo[TCORR_COMBO_RSLT],        // [data_size * data_size]
												corr_size,      
												icorr_max,          // integer center coordinates (relative to top left)
												corr_max_weights_poly,   // [(radius+1) * (radius+1)]
												max_search_radius_poly,                  // max_search_radius, for polynomial - always use 1
												debugMax);
										if (corr_max_XY != null){
											disparity_map[DISPARITY_INDEX_POLY][tIndex] = transform_size - 1 -corr_max_XY[0];
											disparity_map[DISPARITY_INDEX_POLY+1][tIndex] = transform_size - 1 -corr_max_XY[1];
										} else {
											disparity_map[DISPARITY_INDEX_POLY][tIndex] = Double.NaN;
											disparity_map[DISPARITY_INDEX_POLY+1][tIndex] = Double.NaN;
										}
										if      (corr_mode == 0) extra_disparity = disparity_map[DISPARITY_INDEX_INT][tIndex];
										else if (corr_mode == 1) extra_disparity = disparity_map[DISPARITY_INDEX_CM][tIndex];
										else if (corr_mode == 2) extra_disparity = disparity_map[DISPARITY_INDEX_POLY][tIndex];
										else if (corr_mode == 3) extra_disparity = disparity_map[DISPARITY_INDEX_HOR][tIndex];
										else if (corr_mode == 4) extra_disparity = disparity_map[DISPARITY_INDEX_VERT][tIndex];
										if (Double.isNaN(extra_disparity)) extra_disparity = 0;
										
										if (clt_mismatch != null){
											for (int pair = 0; pair < corr_pairs.length; pair++) if (((corr_mask >> pair) & 1) != 0){
												icorr_max =getMaxXYInt( // find integer pair or null if below threshold
														tcorr_partial[pair][numcol],      // [data_size * data_size]
														corr_size,      
														min_corr,    // minimal value to consider (at integer location, not interpolated)
														debugMax);
												if (icorr_max == null){
													clt_mismatch[3*pair + 0 ][tIndex] = Double.NaN;
													clt_mismatch[3*pair + 1 ][tIndex] = Double.NaN;
													clt_mismatch[3*pair + 2 ][tIndex] = Double.NaN;
												} else {
													double [] corr_max_XYmp = getMaxXYCm( // get fractiona center as a "center of mass" inside circle/square from the integer max
															tcorr_partial[pair][numcol],      // [data_size * data_size]
															corr_size,      
															icorr_max, // integer center coordinates (relative to top left)
															max_corr_radius,  // positive - within that distance, negative - within 2*(-radius)+1 square
															max_corr_double, //"Double pass when masking center of mass to reduce preference for integer values
															debugMax); // should never return null
													// Only use Y components for pairs 0,1 and X components - for pairs 2,3
													double yp,xp;
													if (corr_pairs[pair][2] > 0){ // transpose - switch x <-> y
														yp = transform_size - 1 -corr_max_XYmp[0] - disparity_map[DISPARITY_INDEX_CM][tIndex];
														xp = transform_size - 1 -corr_max_XYmp[1]; // do not campare to average - it should be 0 anyway
														
													} else {
														xp = transform_size - 1 -corr_max_XYmp[0] - disparity_map[DISPARITY_INDEX_CM][tIndex];
														yp = transform_size - 1 -corr_max_XYmp[1]; // do not campare to average - it should be 0 anyway
													}
													double strength = tcorr_partial[pair][numcol][max_index]; // using the new location than for combined
													clt_mismatch[3*pair + 0 ][tIndex] = xp;
													clt_mismatch[3*pair + 1 ][tIndex] = yp;
													clt_mismatch[3*pair + 2 ][tIndex] = strength;
												}
											}											
										}
									}
								}
								
							}
						} // end of if (clt_corr_combo != null)
						
						if (texture_tiles !=null) {

//							if ((extra_disparity != 0) && (((1 << FORCE_DISPARITY_BIT) & tile_op[tileY][tileX]) == 0)){ // 0 - adjust disparity, 1 - use provided
							if ((extra_disparity != 0) && !getForcedDisparity(tile_op[tileY][tileX])){ // 0 - adjust disparity, 1 - use provided
								// shift images by 0.5 * extra disparity in the diagonal direction 
								for (int chn = 0; chn <numcol; chn++) { // color
									for (int i = 0; i < quad; i++) {
										fract_shift(    // fractional shift in transform domain. Currently uses sin/cos - change to tables with 2? rotations
												clt_data[i][chn][tileY][tileX], // double  [][]  clt_tile,
												transform_size,
												extra_disparity * port_offsets[i][0] / corr_magic_scale,     // double        shiftX,
												extra_disparity * port_offsets[i][1] / corr_magic_scale,     // double        shiftY,
												//									(globalDebugLevel > 0) && (tileX == debug_tileX) && (tileY == debug_tileY)); // external tile compare
												((globalDebugLevel > 0) && (chn==0) && (tileX >= debug_tileX - 2) && (tileX <= debug_tileX + 2) &&
														(tileY >= debug_tileY - 2) && (tileY <= debug_tileY+2)));									
									}
								}
							}
							// lpf tiles (same as images before)
							// iclt tiles
							double [][][] iclt_tile = new double [quad][numcol][];
							double [] clt_tile;
							double scale = 0.25;  // matching iclt_2d
							for (int i = 0; i < quad; i++) {
								for (int chn = 0; chn <numcol; chn++) { // color
									// double [] clt_tile = new double [transform_size*transform_size];
									for (int dct_mode = 0; dct_mode < 4; dct_mode++){
										clt_tile = clt_data[i][chn][tileY][tileX][dct_mode].clone();
										// lpf each of the 4 quadrants before idct
										for (int j = 0; j < filter.length; j++){
											clt_tile[j] *= scale*filter[j];
										}
										// IDCT-IV should be in reversed order: CC->CC, SC->CS, CS->SC, SS->SS 
										int idct_mode = ((dct_mode << 1) & 2) | ((dct_mode >> 1) & 1);
										clt_tile = dtt.dttt_iv  (clt_tile, idct_mode, transform_size);
										// iclt_tile[i][chn] = dtt.dttt_iv  (clt_data[i][chn][tileY][tileX][dct_mode], idct_mode, transform_size);
										double [] tile_mdct = dtt.unfold_tile(clt_tile, transform_size, dct_mode); // mode=0 - DCCT 16x16
										// accumulate partial mdct results
										if (dct_mode == 0){
											iclt_tile[i][chn] = tile_mdct;
										} else{
											for (int j = 0; j<tile_mdct.length; j++){
												iclt_tile[i][chn][j] += tile_mdct[j]; // matching iclt_2d
											}
										}
									}
								}
							}
							if ((globalDebugLevel > 0) && debugTile) {
								showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
								String [] titles = {"red0","blue0","green0","red1","blue1","green1","red2","blue2","green2","red3","blue3","green3"};
								double [][] dbg_tile = new double [quad*numcol][];
								for (int i = 0; i < quad; i++) {
									for (int chn = 0; chn <numcol; chn++) { // color
										dbg_tile[i * numcol + chn] = iclt_tile[i][chn];
									}
								}
								sdfa_instance.showArrays(dbg_tile, 2* transform_size, 2* transform_size, true, "iclt_x"+tileX+"_y"+tileY, titles);
							}


							// "de-bayer" tiles for matching, use original data for output
							double [][][] tiles_debayered = new double [quad][numcol][];
							for (int i =0; i<quad; i++){
								for (int chn = 0; chn < numcol; chn++){
									//								tiles_debayered[i][chn] =  tile_debayer(
									//										(chn != 2), // red or blue (flase - green)
									//										iclt_tile[i][chn],
									//										2 * transform_size);

									tiles_debayered[i][chn] =  tile_debayer_shot_corr(
											(chn != 2), // red or blue (flase - green)
											iclt_tile[i][chn],
											2 * transform_size,
											lt_window2, // squared lapping window
											min_shot,   // 10.0;  // Do not adjust for shot noise if lower than
											scale_shot,  //3.0;   // scale when dividing by sqrt
											lt_window2); // re-apply window to the result
								}
							}
							if ((globalDebugLevel > 0) && debugTile) {
								showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
								String [] titles = {"red0","blue0","green0","red1","blue1","green1","red2","blue2","green2","red3","blue3","green3"};
								double [][] dbg_tile = new double [quad*numcol][];
								for (int i = 0; i < quad; i++) {
									for (int chn = 0; chn <numcol; chn++) { // color
										dbg_tile[i * numcol + chn] = tiles_debayered[i][chn];
									}
								}
								sdfa_instance.showArrays(dbg_tile, 2* transform_size, 2* transform_size, true, "tiles_debayered_x"+tileX+"_y"+tileY, titles);
							}
							
							double []     max_diff = null;
							if ((disparity_map != null) && (disparity_map.length >= (IMG_DIFF0_INDEX + quad))){
								max_diff = new double[quad];
							}
							texture_tiles[tileY][tileX] =  tile_combine_rgba(
									tiles_debayered, // iclt_tile,      // [port][numcol][256]
									max_diff,        // maximal (weighted) deviation of each channel from the average
									lt_window2,      // [256]
									port_offsets,    // [port]{x_off, y_off}
									img_mask,        // which port to use, 0xf - all 4 (will modify as local variable)
									diff_sigma,      // pixel value/pixel change
									diff_threshold,  // pixel value/pixel change
									diff_gauss,      // when averaging images, use gaussian around average as weight (false - sharp all/nothing)
									min_agree,       // minimal number of channels to agree on a point (real number to work with fuzzy averages)
									col_weights,     // color channel weights, sum == 1.0
									dust_remove,     // boolean dust_remove,    // Do not reduce average weight when only one image differes much from the average
									keep_weights,    // keep_weights);   // return channel weights after A in RGBA
									(globalDebugLevel > 0) && debugTile);

							// mix RGB from iclt_tile, mix alpha with - what? correlation strength or 'don't care'? good correlation or all > min?
							for (int i = 0; i < iclt_tile[0][0].length; i++ ) {
								double sw = 0.0;
								for (int ip = 0; ip < quad; ip++) {
									sw += texture_tiles[tileY][tileX][numcol+1+ip][i];
								}
								if (sw != 0 ) sw = 1.0/sw;
								for (int chn = 0; chn <numcol; chn++) { // color
									texture_tiles[tileY][tileX][chn][i] = 0.0; //iclt[tileY][tileX][chn]
									for (int ip = 0; ip < quad; ip++) {
										texture_tiles[tileY][tileX][chn][i] += sw * texture_tiles[tileY][tileX][numcol+1+ip][i] * iclt_tile[ip][chn][i];
									}
								}
							}
							if ((disparity_map != null) && (disparity_map.length >= (IMG_DIFF0_INDEX + quad))){
								for (int i = 0; i < max_diff.length; i++){
									disparity_map[IMG_DIFF0_INDEX + i][tIndex] = max_diff[i]; 
								}
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return clt_data;
	}
	
	
	public double [][] tile_combine_rgba(
			double [][][] iclt_tile,    // [port][numcol][256]
			double []     max_diff,       // maximal (weighted) deviation of each channel from the average
			double []     lt_window,    // [256]
			double [][]   port_offsets, // [port]{x_off, y_off} - just to scale pixel value differences
			int           port_mask,      // which port to use, 0xf - all 4 (will modify as local variable)
			double        diff_sigma,     // pixel value/pixel change
			double        diff_threshold, // pixel value/pixel change
			boolean       diff_gauss,     // when averaging images, use gaussian around average as weight (false - sharp all/nothing)
			double        min_agree,      // minimal number of channels to agree on a point (real number to work with fuzzy averages)
			double []     chn_weights,     // color channel weights, sum == 1.0
			boolean       dust_remove,    // Do not reduce average weight when only one image differes much from the average
			boolean       keep_weights,   // return channel weights after A in RGBA
			boolean       debug)
	{
		int ports =  iclt_tile.length;
		int numcol =  iclt_tile[0].length;
		int tile_len = iclt_tile[0][0].length;
		int usedPorts = ((port_mask >> 0) & 1) + ((port_mask >> 1) & 1) + ((port_mask >> 2) & 1) + ((port_mask >> 3) & 1);
		
				
		double [][] port_weights = new double[ports][tile_len];
		double [][] color_avg =    new double[numcol][tile_len];
		double [][] rgba = new double[numcol + 1 + (keep_weights?(ports + 4):0)][];
		int rms_start = numcol + 1 + ports;
		if (keep_weights){
			for (int chn = 0; chn <= numcol ; chn++){
				rgba[rms_start + chn] = new double [tile_len]; // rms for each color, then - weighted
			}
			for (int i = 0; i <tile_len; i++){
				double sw = 0.0;
				for (int chn = 0; chn < numcol; chn ++ ){
					double s0 = 0, s1 = 0, s2 = 0;
					for (int ip = 0; ip < ports; ip++)if ((port_mask & ( 1 << ip)) != 0){
						s0 += 1;
						s1 += iclt_tile[ip][chn][i];
						s2 += iclt_tile[ip][chn][i]*iclt_tile[ip][chn][i];
					}
					rgba[rms_start+chn][i] = Math.sqrt(s0*s2 - s1*s1) / s0;
					sw += chn_weights[chn]*rgba[rms_start+chn][i]*rgba[rms_start+chn][i];
				}
				rgba[rms_start+numcol][i] = Math.sqrt(sw); // will fade as window
			}
		}
		
		
		double []  alpha = new double[tile_len];
		double threshold2 = diff_sigma * diff_threshold;
		threshold2 *= threshold2; // squared to compare with diff^2
		if (usedPorts > 1) {
			double [] pair_dist2r =  new double [ports*(ports-1)/2]; // reversed squared distance between images - to be used with gaussian
			int [][]  pair_ports = new int [ports*(ports-1)/2][2];
			int indx = 0;
			double ksigma = 1.0/(2.0*diff_sigma*diff_sigma); // multiply by a weighted sum of squares of the differences
			for (int i = 0; i < ports; i++) if ((port_mask & ( 1 << i)) != 0){
				for (int j = i+1; j < ports; j++)  if ((port_mask & ( 1 << j)) != 0){
					double dx = port_offsets[j][0] - port_offsets[i][0];
					double dy = port_offsets[j][1] - port_offsets[i][1];
					pair_ports[indx][0] = i;
					pair_ports[indx][1] = j;
					pair_dist2r[indx++] = ksigma/(dx*dx+dy*dy); // 2*sigma^2 * r^2
				}
			}
			// there will be no pairs for a single used port
			for (int i = 0; i < tile_len; i++){
				for (int ip = 0; ip < ports; ip++) port_weights[ip][i] = 0.0;
				for (int ip = 0; ip<pair_ports.length; ip++){
					double d = 0;
					for (int chn = 0; chn < numcol; chn++){
						double dc = iclt_tile[pair_ports[ip][0]][chn][i] - iclt_tile[pair_ports[ip][1]][chn][i];
						dc /= lt_window[i]; // to compensate fading near the edges
						d+= chn_weights[chn]*dc*dc;

					}
					d = Math.exp(-pair_dist2r[ip]*d); // 0.5 for exact match, lower for mismatch. Add this weight to both ports involved
					// Add weight to both channels in a pair
					port_weights[pair_ports[ip][0]][i] +=d;
					port_weights[pair_ports[ip][1]][i] +=d;
				}
				// find 2 best ports (resolving 2 pairs of close values)
				int bestPort1=0;
				for (int ip = bestPort1+1; ip < ports; ip++) if (port_weights[ip][i] > port_weights[bestPort1][i]) bestPort1 = ip;
				int bestPort2 = (bestPort1 == 0)?1:0; 
				for (int ip = bestPort2+1; ip < ports; ip++) if ((ip != bestPort1) && (port_weights[ip][i] > port_weights[bestPort2][i])) bestPort2 = ip;
				// find weighted average between these 2 ports
				double w1 = port_weights[bestPort1][i]/(port_weights[bestPort1][i]+port_weights[bestPort2][i]);
				double w2 = 1.0 - w1;
				for (int chn = 0; chn < numcol; chn++){
					color_avg[chn][i] = w1 * iclt_tile[bestPort1][chn][i] + w2 * iclt_tile[bestPort2][chn][i];
				}
				// recalculate all weights using difference from this average of the best pair
				double [] d2 = new double [ports]; //weighted squared differences
				for (int ip = 0; ip < ports; ip++) if ((port_mask & ( 1 << ip)) != 0){
					d2[ip] = 0;
					for (int chn = 0; chn < numcol; chn++){
						double dc = iclt_tile[ip][chn][i] - color_avg[chn][i];
						dc /= lt_window[i]; // to compensate fading near the edges
						d2[ip]+= chn_weights[chn]*dc*dc;
					}
					port_weights[ip][i] = Math.exp(-ksigma * d2[ip]); 
				}
				// and now make a new average with those weights
				double k = 0.0;
				for (int ip = 0; ip < ports; ip++) k+=port_weights[ip][i];
				k = 1.0/k;
				for (int chn = 0; chn < numcol; chn++){
					color_avg[chn][i] = 0;
					for (int ip = 0; ip < ports; ip++) {
						color_avg[chn][i] += k* port_weights[ip][i] * iclt_tile[ip][chn][i]; 
					}
				}
				
/*				
				
				// remove outlayers (if any) here completely? diff_threshold
				// threshold2
				while ((d2[0] > threshold2) || (d2[1] > threshold2) || (d2[2] > threshold2) || (d2[3] > threshold2)){ // assuming ports==4!
					// find worst channel
					int iworst = 0;
					for (int ip = 1; ip < ports; ip++) if (d2[ip] > d2[iworst]) iworst = ip;
					port_mask &= ~ (1 << iworst); // remove worst port
					port_weights[iworst][i] = 0.0;
					// recalculate new average after worst is removed
					k = 0.0;
					for (int ip = 0; ip < ports; ip++) k+=port_weights[ip][i];
					k = 1.0/k;
					for (int chn = 0; chn < numcol; chn++){
						color_avg[chn][i] = 0;
						for (int ip = 0; ip < ports; ip++) {
							color_avg[chn][i] += k* port_weights[ip][i] * iclt_tile[ip][chn][i]; 
						}
					}
					// recalculate all weights using difference from this average of the best pair
					d2 = new double [ports]; //weighted squared differences
					for (int ip = 0; ip < ports; ip++) if ((port_mask & ( 1 << ip)) != 0){
						d2[ip] = 0;
						for (int chn = 0; chn < numcol; chn++){
							double dc = iclt_tile[ip][chn][i] - color_avg[chn][i];
							dc /= lt_window[i]; // to compensate fading near the edges
							d2[ip]+= chn_weights[chn]*dc*dc;
						}
						port_weights[ip][i] = Math.exp(-ksigma * d2[ip]); 
					}
				}
				// one last time re-average? (weights will change)
				k = 0.0;
				for (int ip = 0; ip < ports; ip++) k+=port_weights[ip][i];
				k = 1.0/k;
				for (int chn = 0; chn < numcol; chn++){
					color_avg[chn][i] = 0;
					for (int ip = 0; ip < ports; ip++) {
						color_avg[chn][i] += k* port_weights[ip][i] * iclt_tile[ip][chn][i]; 
					}
				}
*/				
			} // or (int i = 0; i < tile_len; i++){
			
		} else if (usedPorts > 0){ // just copy from a single channel
			for (int ip = 0; ip < ports; ip++) if ((port_mask & ( 1 << ip)) != 0){
				for (int i = 0; i < tile_len; i++){
					for (int chn = 0; chn < numcol; chn++){
						color_avg[chn][i] = iclt_tile[ip][chn][i];
					}
					port_weights[ip][i] = 1.0; // lt_window[i]; // or use 1.0?
				}
			}
		}
		if (dust_remove && (usedPorts == 4)) {
			dust_remove(port_weights);
		}
		// calculate alpha from channel weights. Start with just a sum of weights?
		for (int i = 0; i < tile_len; i++){
			alpha[i] = 0.0;
			for (int ip = 0; ip < ports; ip++) if ((port_mask & ( 1 << ip)) != 0){
				alpha[i]+=	port_weights[ip][i];
			}
			alpha[i] *= lt_window[i]/usedPorts; // make it configurable?
		}
	
		for (int i = 0; i < numcol; i++) rgba[i] = color_avg[i]; 
		rgba[numcol] = alpha;
		for (int i = 0; i < ports; i++)  rgba[numcol + 1 + i] = port_weights[i];
		if (max_diff != null){
			for (int ip = 0; ip < ports; ip++){
				max_diff[ip] = 0;
				if ((port_mask & ( 1 << ip)) != 0) {
					for (int i = 0; i < tile_len; i++){
						double d2 = 0.0;
						for (int chn = 0; chn < numcol; chn++){
							double dc = (iclt_tile[ip][chn][i]-color_avg[chn][i]);
							d2+=dc*dc*chn_weights[chn];
						}
						d2 *=lt_window[i];
						if (d2 > max_diff[ip]) max_diff[ip]  = d2;
					}
				}
				max_diff[ip] = Math.sqrt(max_diff[ip]);
			}
		}
		
		return rgba;
	}

	public void dust_remove( // redistribute weight between 3 best ports (use only when all 3 are enabled)
			double [][]  port_weights)
	{
		int np = port_weights.length;
		for (int i = 0; i < port_weights[0].length; i++){
			
			int wi = 0;
			for (int ip = 1; ip < np; ip++) if (port_weights[ip][i] < port_weights[wi][i]) wi = ip;
			double avg = 0;
			for (int ip = 1; ip < np; ip++) if (ip != wi) avg += port_weights[ip][i];
			avg /= (np -1);
			double scale = 1.0 + (avg - port_weights[wi][i])/(avg * (np -1));
			for (int ip = 1; ip < np; ip++) {
				if (ip != wi) port_weights[ip][i] *= scale; // increase weight of non-worst, so if worst == 0.0 sum of 3 (all) ports will be scaled by 4/3, keeping average
			}
			port_weights[wi][i] *= port_weights[wi][i]/avg;
		}
	}
	
	
	public double [] tile_debayer_shot_corr(
			boolean   rb,
			double [] tile,
			int tile_size,
			double [] window2, // squared lapping window
			double min_shot,   // 10.0;  // Do not adjust for shot noise if lower than
			double scale_shot,  //3.0;   // scale when dividing by sqrt
			double [] window_back) // re-apply window to the result
	{
		double [] tile_nw = new double [tile.length];
		for (int i = 0; i < tile.length; i++) tile_nw[i] = tile[i]/window2[i]; //unapply squared window
		double [] tile_db = tile_debayer(
				rb,
				tile_nw,
				tile_size);
		if (scale_shot > 0){
			double k = 1.0/Math.sqrt(min_shot);
			for (int i = 0; i < tile.length; i++) tile_db[i] = scale_shot* ((tile_db[i] > min_shot)? Math.sqrt(tile_db[i]) : (k*tile_db[i]));
		}
		if (window_back != null) {
			for (int i = 0; i < tile.length; i++) tile_db[i] = tile_db[i] * window_back[i]; // optionally re-apply window (may bve a different one)
		}
		return tile_db;
	}
	
	
	public double [] tile_debayer(
			boolean   rb,
			double [] tile,
			int tile_size)
	{
		int [] neib_indices = {-tile_size - 1, -tile_size, -tile_size + 1, -1, 0, 1, tile_size - 1, tile_size, tile_size + 1};
		int tsm1 = tile_size - 1;
		double [] rslt = new double [tile_size*tile_size]; // assuming cleared to 0.0;
		double [] kern = rb ? kern_rb : kern_g;
		double k_corn = rb? (16.0/9.0):(4.0/3.0);
		double k_side = rb? (4.0/3.0):(8.0/7.0);
		// top left 
		int indx = 0;
		int side_type = 0;
		for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
			int dr = corn_side_indices[side_type][dri]; // middle left
			rslt[indx]+=tile[indx+neib_indices[dr]]*kern[dr];
		}
		rslt[indx] *= k_corn;
		// top middle
		side_type = 1;
		for (int j = 1; j < tsm1; j++){
			indx = j;
			for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
				int dr = corn_side_indices[side_type][dri]; // middle left
				rslt[indx]+=tile[indx + neib_indices[dr]] * kern[dr];
			}
			rslt[indx] *= k_side;
		}
		// top right 
		indx = tsm1;
		side_type = 2;
		for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
			int dr = corn_side_indices[side_type][dri]; // middle left
			rslt[indx]+=tile[indx+neib_indices[dr]]*kern[dr];
		}
		rslt[indx] *= k_corn;
		// middle left
		side_type = 3;
		for (int i = 1; i < tsm1; i++){
			indx = i* tile_size; 
			for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
				int dr = corn_side_indices[side_type][dri]; // middle left
				rslt[indx]+=tile[indx + neib_indices[dr]] * kern[dr];
			}
			rslt[indx] *= k_side;
		}
		// middle middle
		side_type = 4;
		for (int i = 1; i < tsm1; i++){
			for (int j = 1; j < tsm1; j++){
				indx = i*tile_size+j;
				rslt[indx] = 0.0;
				for (int dr = 0; dr < neib_indices.length; dr++){
					rslt[indx]+=tile[indx+neib_indices[dr]]*kern[dr];
				}
			}
		}

		// middle right
		side_type = 5;
		for (int i = 1; i < tsm1; i++){
			indx = i* tile_size + tsm1; 
			for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
				int dr = corn_side_indices[side_type][dri]; // middle left
				rslt[indx]+=tile[indx + neib_indices[dr]] * kern[dr];
			}
			rslt[indx] *= k_side;
		}

		// bottom left 
		indx = tsm1*tile_size;
		side_type = 6;
		for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
			int dr = corn_side_indices[side_type][dri]; // middle left
			rslt[indx]+=tile[indx+neib_indices[dr]]*kern[dr];
		}
		rslt[indx] *= k_corn;
		// bottom middle
		side_type = 7;
//		tsm1*tile_size;
		for (int j = 1; j < tsm1; j++){
			indx++;
			for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
				int dr = corn_side_indices[side_type][dri]; // middle left
				rslt[indx]+=tile[indx + neib_indices[dr]] * kern[dr];
			}
			rslt[indx] *= k_side;
		}

		// bottom right 
		indx++; // = tile_size*tile_size-1;
		side_type = 8;
		for (int dri = 0; dri < corn_side_indices[side_type].length; dri++) {
			int dr = corn_side_indices[side_type][dri]; // middle left
			rslt[indx]+=tile[indx+neib_indices[dr]]*kern[dr];
		}
		rslt[indx] *= k_corn;
		return rslt;
	}
	//final int    []   neib_indices = {-width-1,-width,-width+1,-1,0,1,width-1,width,width+1};       
	
	
	// return weights for positive x,y, [(radius+a)*(radius+1)]
	public double [] setMaxXYWeights(
			double sigma,
			int    radius){ // ==3.0, ignore data outside sigma * nSigma
			 // 
		double [] weights = new double [(radius + 1)*(radius + 1)];
		int indx = 0;
		for (int i = 0; i <= radius; i ++){
			for (int j = 0; j <= radius; j ++){
				weights[indx++] = Math.exp(-(i*i+j*j)/(2*sigma*sigma));
			}
		}
		return weights;
	}
	
	// find interpolated location of maximum, return {x,y} or null (if too low or non-existing)
	
	public int [] getMaxXYInt( // find integer pair or null if below threshold
			double [] data,      // [data_size * data_size]
			int       data_size,
			double    minMax,    // minimal value to consider (at integer location, not interpolated)
			boolean   debug)
	{
		int    imx = 0;
		for (int i = 1; i < data.length; i++){
			if (data[imx] < data[i]){
				imx = i;
			}
		}
		if (data[imx] < minMax){
			if (debug){
					System.out.println("getMaxXYInt() -> null (data["+imx+"] = "+data[imx]+" < "+minMax);
			}
			return null;	
		}
		int [] rslt = {imx %  data_size, imx /  data_size};
		if (debug){
			System.out.println("getMaxXYInt() -> "+rslt[0]+"/"+rslt[1]);
		}
		return rslt;
	}
	
	public double [] getMaxXYCm( // get fractiona center as a "center of mass" inside circle/square from the integer max
			double [] data,      // [data_size * data_size]
			int       data_size,
			int []    icenter, // integer center coordinates (relative to top left)
			double    radius,  // positive - within that distance, negative - within 2*(-radius)+1 square
			boolean   max_corr_double,
			boolean   debug)
	{
		if (icenter == null) {
			double [] rslt = {Double.NaN,Double.NaN};
			return rslt; //gigo
		}
		//calculate as "center of mass"
		int iradius = (int) Math.abs(radius);
		int ir2 = (int) (radius*radius);
		boolean square = radius <0;
		double s0 = 0, sx=0,sy = 0;
		for (int y = - iradius ; y <= iradius; y++){
			int dataY = icenter[1] +y;
			if ((dataY >= 0) && (dataY < data_size)){
				int y2 = y*y;
				for (int x = - iradius ; x <= iradius; x++){
					int dataX = icenter[0] +x; 
					double r2 = y2 + x * x;
//					if ((dataX >= 0) && (dataX < data_size) && (square || ((y2 + x * x) <= ir2))){
					if ((dataX >= 0) && (dataX < data_size) && (square || (r2 <= ir2))){
//						double w = max_corr_double? (1.0 - r2/ir2):1.0;
//						double d =  w* data[dataY * data_size + dataX];
						double d =  data[dataY * data_size + dataX];
						s0 += d;
						sx += d * dataX;
						sy += d * dataY;
					}
				}
			}
		}
		double [] rslt = {sx / s0, sy / s0};
		if (debug){
			System.out.println("getMaxXYInt() -> "+rslt[0]+"/"+rslt[1]);
		}
		return rslt;
	}

	public double [] getMaxXSOrtho( // get fractional center as a "center of mass" inside circle/square from the integer max
			double [] data,            // [data_size * data_size]
			double [] enhortho_scales, // [data_size]
			int       data_size,
			double    radius,  // positive - within that distance, negative - within 2*(-radius)+1 square
//			boolean   poly_mode,
			boolean   debug)
	{
		double [] corr_1d = new double [data_size];
		for (int j = 0; j < data_size; j++){
			corr_1d[j] = 0;
			for (int i = 0; i < data_size; i++){
				corr_1d[j] += data[i * data_size + j] * enhortho_scales[i];
			}
		}
		int icenter = 0;
		for (int i = 1; i < data_size; i++){
			if (corr_1d[i] > corr_1d[icenter]) icenter = i;
		}
		//calculate as "center of mass"
//		int iradius = (int) Math.abs(radius);
		double [] coeff = null;
		double xcenter = icenter;
		double [][] pa_data=null;
		// try 3-point parabola
		if ((icenter >0) && (icenter < (data_size - 1))) {
			PolynomialApproximation pa = new PolynomialApproximation(debug?5:0); // debugLevel
			double [][] pa_data0 = {
					{icenter - 1,  corr_1d[icenter - 1]},
					{icenter,      corr_1d[icenter    ]},
					{icenter + 1,  corr_1d[icenter + 1]}};
			pa_data = pa_data0;
			coeff = pa.polynomialApproximation1d(pa_data, 2);
			if (coeff != null){
				xcenter = - coeff[1]/(2* coeff[2]);
			}
		}
		icenter = (int) Math.round(xcenter);
		double strength = corr_1d[icenter] / ((data_size+1) / 2);// scale to ~match regular strength
		double [] rslt1 = {xcenter, strength}; 
		return rslt1;
/*		
		double s0 = 0, sx=00;
		int x_min = (int) Math.ceil(xcenter - radius);
		if (x_min < 0) x_min = 0;
		int x_max = (int) Math.floor(xcenter + radius);
		if (x_max >= data_size) x_max = data_size - 1;
		for (int x = x_min ; x <= x_max; x++){
			double d =  corr_1d[x];
			s0 += d;
			sx += d * x;
		}
		double [] rslt = {sx / s0, strength}; // scale to ~match regular strength
		if (debug){
			System.out.println("getMaxXYCmEnhOrtho() -> "+rslt[0]+"/"+rslt[1]);
			for (int i = 0; i < data_size; i++){
				System.out.println("corr_1d["+i+"]="+corr_1d[i]);
			}
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			double [] masked_data = new double [data_size*data_size];
			for (int j = 0; j < data_size; j++){
				for (int i = 0; i < data_size; i++){
					masked_data[i * data_size + j] = data[i * data_size + j] * enhortho_scales[i];
				}
			}
			double [][] dbg_data = {data,masked_data};
			String [] titles = {"correlation", "enhortho_correlation"};
			sdfa_instance.showArrays(dbg_data,  data_size, data_size, true, "getMaxXYCmEnhOrtho", titles);
			if (coeff != null) 	System.out.println("a = "+coeff[2]+", b = "+coeff[1]+", c = "+coeff[0]);
			System.out.println("xcenter="+xcenter);
			if (pa_data != null) {
				for (int i = 0; i < pa_data.length; i++){
					System.out.println("pa_data["+i+"]={"+pa_data[i][0]+", "+pa_data[i][1]+"}");
				}
			}
		}
		return rslt;
*/		
	}
	
	
	public double [] getMaxXYPoly( // get interpolated maximum coordinates using 2-nd degree polynomial
			PolynomialApproximation pa,
			double [] data,      // [data_size * data_size]
			int       data_size,
			int []    icenter, // integer center coordinates (relative to top left)
			double [] weights,   // [(radius+1) * (radius+1)]
			int       radius,
			boolean   debug)
	{
		// TODO: make sure it is within 1pxx1px square from the integer maximum? If not - return null and use center of mass instead?
		if (pa == null) pa = new PolynomialApproximation();
		if (icenter == null) return null; //gigo
		
		double [][] zdata = {{0.0,0.0},{0.0},{0.0}};
//		radius = 1;
		double [][][] mdata = new double[(2 * radius + 1) * (2 * radius + 1)][3][];
		int indx = 0;
		for (int y = - radius ; y <= radius; y++){
			int dataY = icenter[1] +y;
			if ((dataY >= 0) && (dataY < data_size)){
				int ay = (y >= 0)?y:-y; 
				for (int x = - radius ; x <= radius; x++){
					int dataX = icenter[0] +x; 
					if ((dataX >= 0) && (dataX < data_size)){
						int ax = (x >= 0) ? x: -x;
						mdata[indx][0] = new double [2];
						mdata[indx][0][0] =  dataX;
						mdata[indx][0][1] =  dataY;
						mdata[indx][1] = new double [1];
						mdata[indx][1][0] =  data[dataY * data_size + dataX];
						mdata[indx][2] = new double [1];
						mdata[indx][2][0] =  weights[ay * (radius + 1) + ax];
						indx++;
					}
				}
			}
		}
		for (;indx <  mdata.length; indx++){
			mdata[indx] = zdata;
		}
		if (debug){
			System.out.println("before: getMaxXYPoly(): icenter[0] = "+icenter[0]+" icenter[1] = "+icenter[1]);
			
			for (int i = 0; i< mdata.length; i++){
				System.out.println(i+": "+mdata[i][0][0]+"/"+mdata[i][0][1]+" z="+mdata[i][1][0]+" w="+mdata[i][2][0]);
			}
		}
		double [] rslt = pa.quadraticMax2d(
				mdata,
				1.0E-30,//25, // 1.0E-15,
				debug? 4:0);
		if (debug){
			System.out.println("after: getMaxXYPoly(): icenter[0] = "+icenter[0]+" icenter[1] = "+icenter[1]);
			for (int i = 0; i< mdata.length; i++){
				System.out.println(i+": "+mdata[i][0][0]+"/"+mdata[i][0][1]+" z="+mdata[i][1][0]+" w="+mdata[i][2][0]);
			}
			System.out.println("quadraticMax2d(mdata) --> "+((rslt==null)?"null":(rslt[0]+"/"+rslt[1])));
		}
		return rslt;
	}
	
	
// perform 2d clt, result is [tileY][tileX][cc_sc_cs_ss][index_in_tile]
	public double [][][][] clt_2d(
			final double [] dpixels,
			final int       width,
			final int       dct_size,
			final int       window_type,
			final int       shiftX, // shift image horizontally (positive - right)
			final int       shiftY, // shift image vertically (positive - down)
			final int       debug_tileX,
			final int       debug_tileY,
			final int       debug_mode,
			final int       threadsMax,  // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
		final int height=dpixels.length/width;
		final int tilesX=width/dct_size-1;
		final int tilesY=height/dct_size-1;
		final int nTiles=tilesX*tilesY; 
		final double [][][][] dct_data = new double[tilesY][tilesX][4][dct_size*dct_size];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int tileY = 0; tileY < tilesY; tileY++){
			for (int tileX = 0; tileX < tilesX; tileX++){
				for (int dct_mode = 0; dct_mode < dct_data[tileY][tileX].length; dct_mode++){
					for (int i=0; i<dct_data[tileY][tileX][dct_mode].length;i++) {
						dct_data[tileY][tileX][dct_mode][i]= 0.0; // actually not needed, Java initializes arrays
					}
				}
			}
		}
		double [] dc = new double [dct_size*dct_size];
		for (int i = 0; i<dc.length; i++) dc[i] = 1.0;
		DttRad2 dtt0 = new DttRad2(dct_size);
		dtt0.set_window(window_type);
		if (globalDebugLevel > 0) {
			System.out.println("clt_2d(): width="+width+" height="+height+" dct_size="+dct_size+
					" debug_tileX="+debug_tileX+" debug_tileY="+debug_tileY+" globalDebugLevel="+globalDebugLevel);
		}
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(dct_size);
					dtt.set_window(window_type);
					double [] tile_in = new double[4*dct_size * dct_size];
					double [][] tile_folded = new double[4][];
					double [][] tile_out =    new double[4][]; // = new double[dct_size * dct_size];
					int tileY,tileX;
					int n2 = dct_size * 2;
//					showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						if ((shiftX == 0) && (shiftY == 0)){
							for (int i = 0; i < n2;i++){
								System.arraycopy(dpixels, (tileY*width+tileX)*dct_size + i*width, tile_in, i*n2, n2);
							}
						} else {
							int x0 = tileX * dct_size - shiftX;
							if      (x0 < 0)             x0 = 0; // first/last will be incorrect
							else if (x0 >= (width - n2)) x0 = width - n2;  
							for (int i = 0; i < n2;i++){
								int y0 = tileY * dct_size + i - shiftY;
								if      (y0 < 0)       y0 = 0;
								else if (y0 >= height) y0 = height -1;
								System.arraycopy(dpixels, y0 * width+ x0, tile_in, i*n2, n2);
							}
						}
						for (int dct_mode = 0; dct_mode <4; dct_mode++) {
							tile_folded[dct_mode] = dtt.fold_tile(tile_in, dct_size, dct_mode); // DCCT, DSCT, DCST, DSST
							if ((debug_mode & 1) != 0) {
								tile_out[dct_mode] = tile_folded[dct_mode];
							} else {
								tile_out[dct_mode] =    dtt.dttt_iv  (tile_folded[dct_mode], dct_mode, dct_size);
							}
							System.arraycopy(tile_out[dct_mode], 0, dct_data[tileY][tileX][dct_mode], 0, tile_out[dct_mode].length);
						}
						if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)) {
							showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
							sdfa_instance.showArrays(tile_in,  n2, n2, "tile_in_x"+tileX+"_y"+tileY);
							String [] titles = {"CC","SC","CS","SS"};
							sdfa_instance.showArrays(tile_folded,  dct_size, dct_size, true, "folded_x"+tileX+"_y"+tileY, titles);
							if (globalDebugLevel > 0) {
								sdfa_instance.showArrays(tile_out,     dct_size, dct_size, true, "clt_x"+tileX+"_y"+tileY, titles);
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return dct_data;
	}
	
	public double [] iclt_2d(
			final double [][][][] dct_data,  // array [tilesY][tilesX][4][dct_size*dct_size]  
			final int             dct_size,
			final int             window_type,
			final int             debug_mask, // which transforms to combine
			final int             debug_mode, // skip idct - just unfold
			final int             threadsMax,  // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;

//		final int width=  (tilesX+1)*dct_size;
//		final int height= (tilesY+1)*dct_size;
		final int width=  tilesX * dct_size;
		final int height= tilesY * dct_size;
		final double debug_scale = 1.0 /((debug_mask & 1) + ((debug_mask >> 1) & 1) + ((debug_mask >> 2) & 1) + ((debug_mask >> 3) & 1));
		if (globalDebugLevel > 0) {
			System.out.println("iclt_2d():tilesX=        "+tilesX);
			System.out.println("iclt_2d():tilesY=        "+tilesY);
			System.out.println("iclt_2d():width=         "+width);
			System.out.println("iclt_2d():height=        "+height);
			System.out.println("iclt_2d():debug_mask=    "+debug_mask);
			System.out.println("iclt_2d():debug_scale=   "+debug_scale);
		}
		final double [] dpixels = new double[width*height];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		final AtomicInteger nser = new AtomicInteger(0);
		final int [][][] tiles_list = new int[4][][];
		for (int n=0; n<4; n++){
			int nx = (tilesX + 1 - (n &1)) / 2;
			int ny = (tilesY + 1 - ((n>>1) & 1)) / 2;
			tiles_list[n] = new int [nx*ny][2];
			int indx = 0;
			for (int i = 0;i < ny; i++) for (int j = 0; j < nx; j++){
				tiles_list[n][indx][0]=2*j+(n &1);
				tiles_list[n][indx++][1]=2*i+((n>>1) & 1);
			}
		}
		for (int i=0; i<dpixels.length;i++) dpixels[i]= 0;
		for (int n=0; n<4; n++){
			nser.set(n);
			ai.set(0);
			for (int ithread = 0; ithread < threads.length; ithread++) {
				threads[ithread] = new Thread() {
					public void run() {
						DttRad2 dtt = new DttRad2(dct_size);
						dtt.set_window(window_type);
						double [] tile_in =   new double [dct_size * dct_size];
						double [] tile_dct;
						double [] tile_mdct;
						int tileY,tileX;
						int n2 = dct_size * 2;
						int n_half = dct_size / 2;
						int lastY = tilesY-1;
						int lastX = tilesX-1;
						int offset = n_half * (dct_size * tilesX) + n_half; 
						for (int nTile = ai.getAndIncrement(); nTile < tiles_list[nser.get()].length; nTile = ai.getAndIncrement()) {
							tileX = tiles_list[nser.get()][nTile][0];
							tileY = tiles_list[nser.get()][nTile][1];
							if (dct_data[tileY][tileX] != null){
								for (int dct_mode = 0; dct_mode < 4; dct_mode++) if (((1 << dct_mode) & debug_mask) != 0) {
									System.arraycopy(dct_data[tileY][tileX][dct_mode], 0, tile_in, 0, tile_in.length);
									if ((debug_mode & 1) != 0) {
										tile_dct = tile_in;
									} else {
										// IDCT-IV should be in reversed order: CC->CC, SC->CS, CS->SC, SS->SS 
										int idct_mode = ((dct_mode << 1) & 2) | ((dct_mode >> 1) & 1); 
										tile_dct = dtt.dttt_iv  (tile_in, idct_mode, dct_size);
									}
									tile_mdct = dtt.unfold_tile(tile_dct, dct_size, dct_mode); // mode=0 - DCCT
									if ((tileY >0) && (tileX > 0) && (tileY < lastY) && (tileX < lastX)) { // fast, no extra checks
										for (int i = 0; i < n2;i++){
											//									int start_line = ((tileY*dct_size + i) *(tilesX+1) + tileX)*dct_size; 
											int start_line = ((tileY*dct_size + i) * tilesX + tileX)*dct_size - offset; 
											for (int j = 0; j<n2;j++) {
												dpixels[start_line + j] += debug_scale * tile_mdct[n2 * i + j]; // add (cc+sc+cs+ss)/4 
											}
										}
									} else { // be careful with margins
										for (int i = 0; i < n2;i++){
											if (	((tileY > 0) && (tileY < lastY)) ||
													((tileY == 0) && (i >= n_half)) ||
													((tileY == lastY) && (i < (n2 - n_half)))) {
												int start_line = ((tileY*dct_size + i) * tilesX + tileX)*dct_size  - offset; 
												for (int j = 0; j<n2;j++) {
													if (	((tileX > 0) && (tileX < lastX)) ||
															((tileX == 0) && (j >= n_half)) ||
															((tileX == lastX) && (j < (n2 - n_half)))) {
														dpixels[start_line + j] += debug_scale * tile_mdct[n2 * i + j]; // add (cc+sc+cs+ss)/4
													}
												}
											}
										}

									}
								}
							}
						}
					}
				};
			}		      
			startAndJoin(threads);
		}
		return dpixels;
	}

	public double [][] combineRGBATiles(
			final double [][][][] texture_tiles,  // array [tilesY][tilesX][4][4*transform_size] or [tilesY][tilesX]{null}   
			final int             transform_size,
			final boolean         overlap,    // when false - output each tile as 16x16, true - overlap to make 8x8
			final boolean         sharp_alpha, // combining mode for alpha channel: false - treat as RGB, true - apply center 8x8 only 
			final int             threadsMax,  // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=texture_tiles.length;
		final int tilesX=texture_tiles[0].length;

		final int width=  (overlap?1:2)*tilesX * transform_size;
		final int height=  (overlap?1:2)*tilesY * transform_size;
		if (globalDebugLevel > 0) {
			System.out.println("iclt_2d():tilesX=        "+tilesX);
			System.out.println("iclt_2d():tilesY=        "+tilesY);
			System.out.println("iclt_2d():width=         "+width);
			System.out.println("iclt_2d():height=        "+height);
			System.out.println("iclt_2d():overlap=       "+overlap);
			System.out.println("iclt_2d():sharp_alpha=   "+sharp_alpha);
		}
		boolean has_weights = false;
		boolean set_has_weight = false;
		for (int i = 0; (i < tilesY) && !set_has_weight; i++){
			for (int j = 0; (j < tilesX) && !set_has_weight; j++){
				if (texture_tiles[i][j] != null) {
					set_has_weight = true;
					has_weights = texture_tiles[i][j].length > 4;
				}
			}
		}
		
//		final double [][] dpixels = new double["RGBA".length()+(has_weights? 4: 0)][width*height]; // assuming java initializes them to 0
		final double [][] dpixels = new double["RGBA".length()+(has_weights? 8: 0)][width*height]; // assuming java initializes them to 0
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		final AtomicInteger nser = new AtomicInteger(0);
		final int [][][] tiles_list = new int[4][][];
		for (int n=0; n<4; n++){
			int nx = (tilesX + 1 - (n &1)) / 2;
			int ny = (tilesY + 1 - ((n>>1) & 1)) / 2;
			tiles_list[n] = new int [nx*ny][2];
			int indx = 0;
			for (int i = 0;i < ny; i++) for (int j = 0; j < nx; j++){
				tiles_list[n][indx][0]=2*j+(n &1);
				tiles_list[n][indx++][1]=2*i+((n>>1) & 1);
			}
		}
		for (int n=0; n<4; n++){
			nser.set(n);
			ai.set(0);
			for (int ithread = 0; ithread < threads.length; ithread++) {
				threads[ithread] = new Thread() {
					public void run() {
						int tileY,tileX;
						int n2 = transform_size * 2;
						int n_half = transform_size / 2;
						int lastY = tilesY-1;
						int lastX = tilesX-1;
						int offset = n_half * (transform_size * tilesX) + n_half; 
						for (int nTile = ai.getAndIncrement(); nTile < tiles_list[nser.get()].length; nTile = ai.getAndIncrement()) {
							tileX = tiles_list[nser.get()][nTile][0];
							tileY = tiles_list[nser.get()][nTile][1];
							double [][] texture_tile =texture_tiles[tileY][tileX];
							if (texture_tile != null) {
								if (overlap) {
									if ((tileY >0) && (tileX > 0) && (tileY < lastY) && (tileX < lastX)) { // fast, no extra checks
										for (int i = 0; i < n2;i++){
											int start_line = ((tileY*transform_size + i) * tilesX + tileX)*transform_size - offset;
											for (int chn = 0; chn < texture_tile.length; chn++) {
												if ((chn != 3) || !sharp_alpha) {
													for (int j = 0; j<n2;j++) {
														dpixels[chn][start_line + j] += texture_tile[chn][n2 * i + j]; 
													}
												} else if ((i >= n_half) && (i < (n2-n_half))) {
													for (int j = n_half; j < (n2 - n_half); j++) {
														dpixels[chn][start_line + j] += texture_tile[chn][n2 * i + j]; 
													}
												}
											}
										}
									} else { // be careful with margins
										for (int i = 0; i < n2;i++){
											if (	((tileY > 0) && (tileY < lastY)) ||
													((tileY == 0) && (i >= n_half)) ||
													((tileY == lastY) && (i < (n2 - n_half)))) {
												int start_line = ((tileY*transform_size + i) * tilesX + tileX)*transform_size  - offset; 
												for (int chn = 0; chn < texture_tile.length; chn++) {
													if ((chn != 3) || !sharp_alpha) {
														for (int j = 0; j<n2;j++) {
															if (	((tileX > 0) && (tileX < lastX)) ||
																	((tileX == 0) && (j >= n_half)) ||
																	((tileX == lastX) && (j < (n2 - n_half)))) {
																dpixels[chn][start_line + j] += texture_tile[chn][n2 * i + j];
															}
														}
													} else if ((i >= n_half) && (i < (n2-n_half))) {
														for (int j = n_half; j < (n2 - n_half); j++) {
															if (	((tileX > 0) && (tileX < lastX)) ||
																	((tileX == 0) && (j >= n_half)) ||
																	((tileX == lastX) && (j < (n2 - n_half)))) {
																dpixels[chn][start_line + j] += texture_tile[chn][n2 * i + j];
															}
														}
													}
												}
											}
										}

									}
								} else { //if (overlap) - just copy tiles w/o overlapping
									for (int i = 0; i < n2;i++){
										for (int chn = 0; chn < texture_tile.length; chn++) {
											System.arraycopy(
													texture_tile[chn],
													i * n2,
													dpixels[chn],
													(tileY * n2 + i)* width + tileX*n2,
													n2);
										}										
									}
								}
							}
						}
					}
				};
			}		      
			startAndJoin(threads);
		}
		return dpixels;
	}

	
	
	
	public double [][][][] clt_shiftXY(
			final double [][][][] dct_data,  // array [tilesY][tilesX][4][dct_size*dct_size]  
			final int             dct_size,
			final double          shiftX,
			final double          shiftY,
			final int             dbg_swap_mode,
			final int             threadsMax,  // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;
		final int nTiles = tilesY* tilesX; 
		if (globalDebugLevel > 0) {
			System.out.println("clt_shift():tilesX=        "+tilesX);
			System.out.println("clt_shift():tilesY=        "+tilesY);
			System.out.println("clt_shift():shiftX=        "+shiftX);
			System.out.println("clt_shift():shiftY=        "+shiftY);
		}
		final double [] cos_hor =  new double [dct_size*dct_size];
		final double [] sin_hor =  new double [dct_size*dct_size];
		final double [] cos_vert = new double [dct_size*dct_size];
		final double [] sin_vert = new double [dct_size*dct_size];
		for (int i = 0; i < dct_size; i++){
			double ch = Math.cos((i+0.5)*Math.PI*shiftX/dct_size);
			double sh = Math.sin((i+0.5)*Math.PI*shiftX/dct_size);
			double cv = Math.cos((i+0.5)*Math.PI*shiftY/dct_size);
			double sv = Math.sin((i+0.5)*Math.PI*shiftY/dct_size);
			for (int j = 0; j < dct_size; j++){
				int iv = dct_size * j + i; // 2d DTT results are stored transposed! 
				int ih = dct_size * i + j; 
				cos_hor[ih] = ch; 
				sin_hor[ih] = sh; 
				cos_vert[iv] = cv; 
				sin_vert[iv] = sv; 
			}
			
		}

		if (globalDebugLevel > 0){
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			String [] titles = {"cos_hor","sin_hor","cos_vert","sin_vert"};
			double [][] cs_dbg = {cos_hor, sin_hor, cos_vert, sin_vert};
			sdfa_instance.showArrays(cs_dbg,  dct_size, dct_size, true, "shift_cos_sin", titles);
		}
		
		final double [][][][] rslt = new double[dct_data.length][dct_data[0].length][dct_data[0][0].length][dct_data[0][0][0].length];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						// Horizontal shift CLT tiled data is stored in transposed way (horizontal - Y, vertical X) 
						for (int i = 0; i < cos_hor.length; i++) {
							rslt[tileY][tileX][0][i] = dct_data[tileY][tileX][0][i] * cos_hor[i] - dct_data[tileY][tileX][1][i] * sin_hor[i];
							rslt[tileY][tileX][1][i] = dct_data[tileY][tileX][1][i] * cos_hor[i] + dct_data[tileY][tileX][0][i] * sin_hor[i] ;

							rslt[tileY][tileX][2][i] = dct_data[tileY][tileX][2][i] * cos_hor[i]  - dct_data[tileY][tileX][3][i] * sin_hor[i];
							rslt[tileY][tileX][3][i] = dct_data[tileY][tileX][3][i] * cos_hor[i]  + dct_data[tileY][tileX][2][i] * sin_hor[i] ;
						}
						// Vertical shift (in-place)
						for (int i = 0; i < cos_hor.length; i++) {
							double tmp =               rslt[tileY][tileX][0][i] * cos_vert[i] - rslt[tileY][tileX][2][i] * sin_vert[i];
							rslt[tileY][tileX][2][i] = rslt[tileY][tileX][2][i] * cos_vert[i] + rslt[tileY][tileX][0][i] * sin_vert[i];
							rslt[tileY][tileX][0][i] = tmp;

							tmp =                      rslt[tileY][tileX][1][i] * cos_vert[i] - rslt[tileY][tileX][3][i] * sin_vert[i];
							rslt[tileY][tileX][3][i] = rslt[tileY][tileX][3][i] * cos_vert[i] + rslt[tileY][tileX][1][i] * sin_vert[i];
							rslt[tileY][tileX][1][i] = tmp;
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return rslt;
	}

	public double [][][][] clt_correlate(
			final double [][][][] data1,  // array [tilesY][tilesX][4][dct_size*dct_size]  
			final double [][][][] data2,  // array [tilesY][tilesX][4][dct_size*dct_size]  
			final int             dct_size,
			final double          fat_zero,    // add to denominator to modify phase correlation (same units as data1, data2)
			final int             debug_tileX,
			final int             debug_tileY,
			final int             threadsMax,  // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=(data1.length > data2.length)?data2.length:data1.length;
		final int tilesX=(data1[0].length > data2[0].length)?data2[0].length:data1[0].length;
		final int nTiles = tilesY* tilesX;
		if (globalDebugLevel > 0) {
			System.out.println("clt_shift():tilesX= "+tilesX);
			System.out.println("clt_shift():tilesY= "+tilesY);
		}
		/* Direct matrix Z1: X2 ~= Z1 * Shift   
		 * {{+cc  -sc  -cs  +ss},
		 *  {+sc  +cc  -ss  -cs},
		 *  {+cs  -ss  +cc  -sc},
		 *  {+ss  +cs  +sc  +cc}}
		 *  
		 * T= transp({cc, sc, cs, ss}) 
		 */
		/*
		final int [][] zi = 
			{{ 0, -1, -2,  3},
			 { 1,  0, -3, -2},
			 { 2, -3,  0, -1},
			 { 3,  2,  1,  0}};
		*/
		final int [][] zi = 
			{{ 0,  1,  2,  3},
			 {-1,  0, -3,  2},
			 {-2, -3,  0,  1},
			 { 3, -2, -1,  0}};
		
		final int dct_len = dct_size * dct_size;
		final double [][][][] rslt = new double[tilesY][tilesX][4][dct_len];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						for (int i = 0; i < dct_len; i++) {
							double s1 = 0.0, s2=0.0;
							for (int n = 0; n< 4; n++){
								s1+=data1[tileY][tileX][n][i] * data1[tileY][tileX][n][i];
								s2+=data2[tileY][tileX][n][i] * data2[tileY][tileX][n][i];
							}
							double scale = 1.0 / (Math.sqrt(s1*s2) + fat_zero*fat_zero); // squared to match units
							for (int n = 0; n<4; n++){
								/*
								if (
										(tileY >= rslt.length) ||
										(tileX >= rslt[tileY].length) ||
										(n >= rslt[tileY][tileX].length) ||
										(i >= rslt[tileY][tileX][n].length)) {
									
									System.out.println("===== tileY="+tileY+" ("+tilesY+") tileX="+tileX+" ("+tilesX+") n="+n+" i="+i);
								
									System.out.println(
											" rslt.length="+rslt.length+
											" rslt.length[tileY]="+rslt[tileY].length+
											" rslt.length[tileY][tileX]="+rslt[tileY][tileX].length+
											" rslt.length[tileY][tileX][n]="+rslt[tileY][tileX][n].length);
								System.out.println("===== tileY="+tileY+" ("+tilesY+") tileX="+tileX+" ("+tilesX+") n="+n+" i="+i);
								}
								*/
								rslt[tileY][tileX][n][i] = 0;
								for (int k=0; k<4; k++){
									if (zi[n][k] < 0)
										rslt[tileY][tileX][n][i] -= 
											data1[tileY][tileX][-zi[n][k]][i] * data2[tileY][tileX][k][i];
									else
										rslt[tileY][tileX][n][i] += 
										data1[tileY][tileX][zi[n][k]][i] * data2[tileY][tileX][k][i];
								}
								rslt[tileY][tileX][n][i] *= scale;
							}
						}
						if ((globalDebugLevel > 0) && (debug_tileX == tileX) && (debug_tileY == tileY)) {
							showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
							String [] titles = {"CC","SC","CS","SS"};
							sdfa_instance.showArrays(data1[tileY][tileX], dct_size, dct_size, true, "data1_x"+tileX+"_y"+tileY, titles);
							sdfa_instance.showArrays(data2[tileY][tileX], dct_size, dct_size, true, "data2_x"+tileX+"_y"+tileY, titles);
							sdfa_instance.showArrays(rslt[tileY][tileX],  dct_size, dct_size, true, "rslt_x"+ tileX+"_y"+tileY, titles);
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return rslt;
	}

	public void clt_lpf(
			final double          sigma,
			final double [][][][] clt_data,
			final int             dct_size,
			final int             threadsMax,     // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=clt_data.length;
		final int tilesX=clt_data[0].length;
		final int nTiles=tilesX*tilesY;
//		final int dct_size = (int) Math.round(Math.sqrt(clt_data[0][0][0].length));
		final int dct_len = dct_size*dct_size;
		final double [] filter_direct= new double[dct_len];
		if (sigma == 0) {
			filter_direct[0] = 1.0; 
			for (int i= 1; i<filter_direct.length;i++) filter_direct[i] =0; 
		} else {
			for (int i = 0; i < dct_size; i++){
				for (int j = 0; j < dct_size; j++){
					filter_direct[i*dct_size+j] = Math.exp(-(i*i+j*j)/(2*sigma));
				}
			}
		}
		// normalize
		double sum = 0;
		for (int i = 0; i < dct_size; i++){
			for (int j = 0; j < dct_size; j++){
				double d = 	filter_direct[i*dct_size+j];
				d*=Math.cos(Math.PI*i/(2*dct_size))*Math.cos(Math.PI*j/(2*dct_size));
				if (i > 0) d*= 2.0;
				if (j > 0) d*= 2.0;
				sum +=d;
			}
		}
		for (int i = 0; i<filter_direct.length; i++){
			filter_direct[i] /= sum;
		}
		
		if (globalDebugLevel > 1) {
			for (int i=0; i<filter_direct.length;i++){
				System.out.println("dct_lpf_psf() "+i+": "+filter_direct[i]); 
			}
		}
		DttRad2 dtt = new DttRad2(dct_size);
		final double [] filter= dtt.dttt_iiie(filter_direct);
		final double [] dbg_filter= dtt.dttt_ii(filter);
		for (int i=0; i < filter.length;i++) filter[i] *= 2*dct_size;  
		
		if (globalDebugLevel > 1) {
			for (int i=0; i<filter.length;i++){
				System.out.println("dct_lpf_psf() "+i+": "+filter[i]); 
			}
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			double [][] ff = {filter_direct,filter,dbg_filter};
			sdfa_instance.showArrays(ff,  dct_size,dct_size, true, "filter_lpf");
		}
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						if (clt_data[tileY][tileX] != null) {
							for (int n = 0; n < 4; n++){
								for (int i = 0; i < filter.length; i++){
									clt_data[tileY][tileX][n][i] *= filter[i];
								}
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
	}
	
	public void clt_dtt2( // transform dcct2, dsct2, dcst2, dsst2
			final double [][][][] data,
			final boolean         transpose, // when doing inverse transform, the data comes in transposed form, so CS <->SC
			final int             threadsMax,     // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=data.length;
		final int tilesX=data[0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(data[0][0][0].length));
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(dct_size);
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						for (int quadrant = 0; quadrant < 4; quadrant++){
							int mode = transpose ? (((quadrant << 1) & 2) | ((quadrant >> 1) & 1)) : quadrant;
							data[tileY][tileX][quadrant] = dtt.dttt_iie(data[tileY][tileX][quadrant], mode, dct_size);
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
	}
	
	public double [][][] clt_corr_quad( // combine 4 correlation quadrants after DTT2
			final double [][][][] data,
			final int             threadsMax,     // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=data.length;
		final int tilesX=data[0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(data[0][0][0].length));
		final int rslt_size=dct_size*2-1;
		
		final double [][][] rslt = new double[tilesY][tilesX][rslt_size*rslt_size];
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					double scale = 0.25;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						rslt[tileY][tileX][rslt_size*dct_size - dct_size] = scale * data[tileY][tileX][0][0]; // center
						for (int j = 1; j < dct_size; j++) { //  for i == 0
							rslt[tileY][tileX][rslt_size*dct_size - dct_size + j] = scale * (data[tileY][tileX][0][j] + data[tileY][tileX][1][j-1]); 
							rslt[tileY][tileX][rslt_size*dct_size - dct_size - j] = scale * (data[tileY][tileX][0][j] - data[tileY][tileX][1][j-1]); 
						}						
						for (int i = 1; i < dct_size; i++) {
							rslt[tileY][tileX][rslt_size*(dct_size + i) - dct_size] =
									scale * (data[tileY][tileX][0][i*dct_size] + data[tileY][tileX][2][(i-1)*dct_size]); 
							rslt[tileY][tileX][rslt_size*(dct_size - i) - dct_size] =
									scale * (data[tileY][tileX][0][i*dct_size] - data[tileY][tileX][2][(i-1)*dct_size]); 
							for (int j = 1; j < dct_size; j++) {
								rslt[tileY][tileX][rslt_size*(dct_size + i) - dct_size + j] =
										scale * (data[tileY][tileX][0][i*    dct_size + j] + 
												 data[tileY][tileX][1][i*    dct_size + j - 1] +
												 data[tileY][tileX][2][(i-1)*dct_size + j] +
												 data[tileY][tileX][3][(i-1)*dct_size + j - 1]); 
								
								rslt[tileY][tileX][rslt_size*(dct_size + i) - dct_size - j] =
										scale * ( data[tileY][tileX][0][i*    dct_size + j] + 
												 -data[tileY][tileX][1][i*    dct_size + j - 1] +
												  data[tileY][tileX][2][(i-1)*dct_size + j] +
												 -data[tileY][tileX][3][(i-1)*dct_size + j - 1]); 
								rslt[tileY][tileX][rslt_size*(dct_size - i) - dct_size + j] =
										scale * (data[tileY][tileX][0][i*    dct_size + j] + 
												 data[tileY][tileX][1][i*    dct_size + j - 1] +
												 -data[tileY][tileX][2][(i-1)*dct_size + j] +
												 -data[tileY][tileX][3][(i-1)*dct_size + j - 1]);
								rslt[tileY][tileX][rslt_size*(dct_size - i) - dct_size - j] =
										scale * (data[tileY][tileX][0][i*    dct_size + j] + 
												 -data[tileY][tileX][1][i*    dct_size + j - 1] +
												 -data[tileY][tileX][2][(i-1)*dct_size + j] +
												 data[tileY][tileX][3][(i-1)*dct_size + j - 1]); 
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return rslt;
	}
	
	private double [] corr_unfold_tile(
			double [][]  qdata, // [4][transform_size*transform_size] data after DCT2 (pixel domain) 
			int          transform_size
			)
	{
		int corr_pixsize = transform_size * 2 - 1;
		double corr_pixscale = 0.25;
		double [] rslt = new double [corr_pixsize*corr_pixsize];
		rslt[corr_pixsize*transform_size - transform_size] = corr_pixscale * qdata[0][0]; // center
		for (int j = 1; j < transform_size; j++) { //  for i == 0
			rslt[corr_pixsize*transform_size - transform_size + j] = corr_pixscale * (qdata[0][j] + qdata[1][j-1]); 
			rslt[corr_pixsize*transform_size - transform_size - j] = corr_pixscale * (qdata[0][j] - qdata[1][j-1]); 
		}						
		for (int i = 1; i < transform_size; i++) {
			rslt[corr_pixsize*(transform_size + i) - transform_size] =
					corr_pixscale * (qdata[0][i*transform_size] + qdata[2][(i-1)*transform_size]); 
			rslt[corr_pixsize*(transform_size - i) - transform_size] =
					corr_pixscale * (qdata[0][i*transform_size] - qdata[2][(i-1)*transform_size]); 
			for (int j = 1; j < transform_size; j++) {
				rslt[corr_pixsize*(transform_size + i) - transform_size + j] =
						corr_pixscale * (qdata[0][i*    transform_size + j] + 
								 qdata[1][i*    transform_size + j - 1] +
								 qdata[2][(i-1)*transform_size + j] +
								 qdata[3][(i-1)*transform_size + j - 1]); 
				
				rslt[corr_pixsize*(transform_size + i) - transform_size - j] =
						corr_pixscale * ( qdata[0][i*    transform_size + j] + 
								 -qdata[1][i*    transform_size + j - 1] +
								  qdata[2][(i-1)*transform_size + j] +
								 -qdata[3][(i-1)*transform_size + j - 1]); 
				rslt[corr_pixsize*(transform_size - i) - transform_size + j] =
						corr_pixscale * (qdata[0][i*    transform_size + j] + 
								 qdata[1][i*    transform_size + j - 1] +
								 -qdata[2][(i-1)*transform_size + j] +
								 -qdata[3][(i-1)*transform_size + j - 1]);
				rslt[corr_pixsize*(transform_size - i) - transform_size - j] =
						corr_pixscale * (qdata[0][i*    transform_size + j] + 
								 -qdata[1][i*    transform_size + j - 1] +
								 -qdata[2][(i-1)*transform_size + j] +
								 qdata[3][(i-1)*transform_size + j - 1]); 
			}
		}
		
		
		return rslt;
		
	}
	
	
	// extract correlation result  in linescan order (for visualization)
	public double [] corr_dbg(
			final double [][][] corr_data,
			final int           corr_size,
			final double        border_contrast,
			final int           threadsMax,     // maximal number of threads to launch                         
			final int           globalDebugLevel)
	{
		final int tilesY=corr_data.length;
		final int tilesX=corr_data[0].length;
		final int nTiles=tilesX*tilesY;
		final int tile_size = corr_size+1;
		final int corr_len = corr_size*corr_size;
		
		final double [] corr_data_out = new double[tilesY*tilesX*tile_size*tile_size];
		
		System.out.println("corr_dbg(): tilesY="+tilesY+", tilesX="+tilesX+", corr_size="+corr_size+", corr_len="+corr_len);
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int i=0; i<corr_data_out.length;i++) corr_data_out[i]= 0;

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						if (corr_data[tileY][tileX] != null) {
							for (int i = 0; i < corr_size;i++){
								System.arraycopy(corr_data[tileY][tileX], corr_size* i, corr_data_out, ((tileY*tile_size + i) *tilesX + tileX)*tile_size , corr_size);
								corr_data_out[((tileY*tile_size + i) *tilesX + tileX)*tile_size+corr_size] = border_contrast*((i & 1) - 0.5);
							}
							for (int i = 0; i < tile_size; i++){
								corr_data_out[((tileY*tile_size + corr_size) *tilesX + tileX)*tile_size+i] = border_contrast*((i & 1) - 0.5);
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return corr_data_out;
	}

	
	
	
	// extract correlation result  in linescan order (for visualization)
	public double [][] corr_partial_dbg(
			final double [][][][][] corr_data,
			final int corr_size,
			final int pairs,
			final int colors,
			final double            border_contrast,
			final int               threadsMax,     // maximal number of threads to launch                         
			final int               globalDebugLevel)
	{
		final int tilesY=corr_data.length;
		final int tilesX=corr_data[0].length;
		final int nTiles=tilesX*tilesY;
		final int tile_size = corr_size+1;
		final int corr_len = corr_size*corr_size;
		
		System.out.println("corr_partial_dbg(): tilesY="+tilesY+", tilesX="+tilesX+", corr_size="+corr_size+", corr_len="+corr_len+
				" pairs="+pairs +" colors = "+colors+" tile_size="+tile_size);
		
		final double [][] corr_data_out = new double[pairs*colors][tilesY*tilesX*tile_size*tile_size];
//		final String [] colorNames = {"red","blue","green","composite"};
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int pair = 0; pair< pairs; pair++) {
			for (int nColor = 0; nColor < colors; nColor++) {
				for (int i=0; i<corr_data_out.length;i++) corr_data_out[pair*colors+nColor][i]= 0;
			}
		}

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						if (corr_data[tileY][tileX] != null) {
							for (int pair = 0; pair< pairs; pair++) {
								for (int nColor = 0; nColor < colors; nColor++) {
									int indx = pair*colors+nColor;
									for (int i = 0; i < corr_size;i++){
										System.arraycopy(
												corr_data[tileY][tileX][pair][nColor],
												corr_size* i,
												corr_data_out[indx],
												((tileY*tile_size + i) *tilesX + tileX)*tile_size ,
												corr_size);
										corr_data_out[indx][((tileY*tile_size + i) *tilesX + tileX)*tile_size+corr_size] = border_contrast*((i & 1) - 0.5);
									}
									for (int i = 0; i < tile_size; i++){
										corr_data_out[indx][((tileY*tile_size + corr_size) *tilesX + tileX)*tile_size+i] = border_contrast*((i & 1) - 0.5);
									}
								}
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return corr_data_out;
	}

	
	
	
	
	
	public double [][][][][] cltStack(
			final ImageStack                                 imageStack,
			final int                                        subcamera, // 
			final EyesisCorrectionParameters.CLTParameters   cltParameters, //
			final int                                        shiftX, // shift image horizontally (positive - right)
			final int                                        shiftY, // shift image vertically (positive - down)
			final int                                        threadsMax, // maximal step in pixels on the maxRadius for 1 angular step (i.e. 0.5)
			final int                                        debugLevel,
			final boolean                                    updateStatus) // update status info

	{
	  	  if (imageStack==null) return null;
		  final int imgWidth=imageStack.getWidth();
		  final int nChn=imageStack.getSize();
		  double [][][][][] dct_data = new double [nChn][][][][];
		  float [] fpixels;
		  int i,chn; //tileX,tileY;
		  
		  for (chn=0;chn<nChn;chn++) {
			  fpixels= (float[]) imageStack.getPixels(chn+1);
			  double[] dpixels = new double[fpixels.length];
			  for (i = 0; i <fpixels.length;i++) dpixels[i] = fpixels[i];
			  // convert each to DCT tiles
			  dct_data[chn] = clt_2d(
						dpixels,
						imgWidth,
						cltParameters.transform_size,
						cltParameters.clt_window,
						shiftX,
						shiftY,
						cltParameters.tileX,    //       debug_tileX,
						cltParameters.tileY,    //       debug_tileY,
						cltParameters.dbg_mode, //       debug_mode,
						threadsMax,  // maximal number of threads to launch                         
						debugLevel);
		  }
		return dct_data;
	}
	
	
	
	
	// extract DCT transformed parameters in linescan order (for visualization)
	public double [][] clt_dbg(
			final double [][][][] dct_data,
			final int             threadsMax,     // maximal number of threads to launch                         
			final int             globalDebugLevel)
	{
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(dct_data[0][0][0].length));
		final int dct_len = dct_size*dct_size;
		final double [][] dct_data_out = new double[4][tilesY*tilesX*dct_len];
		
		System.out.println("clt_dbg(): tilesY="+tilesY+", tilesX="+tilesX+", dct_size="+dct_size+", dct_len="+dct_len+", dct_data_out[0].length="+dct_data_out[0].length);
		
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int n=0; n<dct_data_out.length;n++) for (int i=0; i<dct_data_out[n].length;i++) dct_data_out[n][i]= 0;

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						for (int n=0; n<dct_data_out.length;n++) {
							for (int i = 0; i < dct_size;i++){
								System.arraycopy(dct_data[tileY][tileX][n], dct_size* i, dct_data_out[n], ((tileY*dct_size + i) *tilesX + tileX)*dct_size , dct_size);
							}
						}
					}
				}
			};
		}		      
		startAndJoin(threads);
		return dct_data_out;
	}

	void clt_convert_double_kernel( // converts double resolution kernel
			double []   src_kernel, //
			double []   dst_kernel, // should be (2*dtt_size-1) * (2*dtt_size-1) + extra_items size - kernel and dx, dy to the nearest 1/2 pixels + actual full center shift)
			int src_size, // 64
			int dtt_size) // 8
	{
		
		int [] indices = {0,-src_size,-1,1,src_size,-src_size-1,-src_size+1,src_size-1,src_size+1};
//		double [] weights = {0.25,0.125,0.125,0.125,0.125,0.0625,0.0625,0.0625,0.0625};
		// sum = 4.0, so sum of all kernels is ~ the same
		double [] weights = {1.0, 0.5,  0.5,  0.5,  0.5,  0.25,  0.25,  0.25,  0.25};
		int src_center = src_size / 2; // 32
		// Find center
		double sx=0.0, sy = 0.0, s = 0.0;
		int indx = 0;
		for (int i= -src_center; i < src_center; i++){
			for (int j = -src_center; j < src_center; j++){
				double d = src_kernel[indx++];
				sx+= j*d;
				sy+= i*d;
				s += d;
			}
		}
		int src_x = (int) Math.round(sx / s) + src_center;
		int src_y = (int) Math.round(sy / s) + src_center;
		// make sure selected area (2*dst_size-1) * (2*dst_size-1) fits into src_kernel, move center if not
		if      (src_x < 2 * dtt_size)             src_x = 2 * dtt_size - 1; // 15
		else if (src_x > (src_size - 2* dtt_size)) src_x = src_size - 2* dtt_size;
		
		if      (src_y < 2 * dtt_size)             src_y = 2 * dtt_size - 1; // 15
		else if (src_y > (src_size - 2* dtt_size)) src_y = src_size - 2* dtt_size;
		indx = 0;
		// downscale, copy
		for (int i = -dtt_size + 1; i < dtt_size; i++){
			int src_i = (src_y + 2 * i) * src_size  + src_x; 
			for (int j = -dtt_size + 1; j < dtt_size; j++){
				double d = 0.0;
				for (int k = 0; k < indices.length; k++){
					d += weights[k]*src_kernel[src_i + 2 * j + indices[k]];
				}
				dst_kernel[indx++] = d;
			}			
		}
		dst_kernel[indx++] = 0.5*(src_x - src_center);
		dst_kernel[indx++] = 0.5*(src_y - src_center);
		dst_kernel[indx++] = 0.5*(sx / s);             // actual center shift in pixels (to interapolate difference to neighbour regions)
		dst_kernel[indx++] = 0.5*(sy / s);
	}

	void clt_normalize_kernel( // 
			double []   kernel, // should be (2*dtt_size-1) * (2*dtt_size-1) + 4 size (last (2*dtt_size-1) are not modified)
			double []   window, // normalizes result kernel * window to have sum of elements == 1.0 
			int dtt_size, // 8
			boolean bdebug)
	{
		double s = 0.0;
		int indx = 0;
		for (int i = -dtt_size + 1; i < dtt_size; i++){
			int ai = (i < 0)? -i: i;
			for (int j = -dtt_size + 1; j < dtt_size; j++){
				int aj = (j < 0)? -j: j;
				s += kernel[indx++] * window[ai*dtt_size+aj];
			}
		}
		s = 1.0/s;
		int klen = (2*dtt_size-1) * (2*dtt_size-1);
		if (bdebug)		System.out.println("clt_normalize_kernel(): s="+s);
		for (int i = 0; i < klen; i++) {
//******************** Somewhere scale 16 ? ********************
			kernel[i] *= 16*s;
		}
 	}

	void clt_symmetrize_kernel( // 
			double []     kernel,      // should be (2*dtt_size-1) * (2*dtt_size-1) +2 size (last 2 are not modified)
			double [][]   sym_kernels, // set of 4 SS, AS, SA, AA kdernels, each dtt_size * dtt_size (may have 5-th with center shift  
			final int     dtt_size) // 8
	{
		int in_size = 2*dtt_size-1;
		int dtt_size_m1 = dtt_size - 1;
		int center = dtt_size_m1 * in_size + dtt_size_m1;
		
		for (int i = 0; i < dtt_size; i++){
			for (int j = 0; j < dtt_size; j++){
				int indx0 = center - i * in_size - j;  
				int indx1 = center - i * in_size + j;  
				int indx2 = center + i * in_size - j;  
				int indx3 = center + i * in_size + j;  
				sym_kernels[0][i*dtt_size+j] =                                 0.25*( kernel[indx0] + kernel[indx1] + kernel[indx2] + kernel[indx3]);
				if (j > 0)              sym_kernels[1][i*dtt_size+j-1] =       0.25*(-kernel[indx0] + kernel[indx1] - kernel[indx2] + kernel[indx3]);
				if (i > 0)              sym_kernels[2][(i-1)*dtt_size+j] =     0.25*(-kernel[indx0] - kernel[indx1] + kernel[indx2] + kernel[indx3]);
				if ((i > 0) && (j > 0)) sym_kernels[3][(i-1)*dtt_size+(j-1)] = 0.25*(-kernel[indx0] + kernel[indx1] - kernel[indx2] + kernel[indx3]);
			}
			sym_kernels[1][i*dtt_size + dtt_size_m1] = 0.0;   
			sym_kernels[2][dtt_size_m1*dtt_size + i] = 0.0;   
			sym_kernels[3][i*dtt_size + dtt_size_m1] = 0.0;   
			sym_kernels[3][dtt_size_m1*dtt_size + i] = 0.0;   
		}
 	}

	void clt_dtt3_kernel( // 
			double [][]   kernels, // set of 4 SS, AS, SA, AA kdernels, each dtt_size * dtt_size (may have 5-th with center shift  
			final int     dtt_size, // 8
			DttRad2       dtt)
	{
		if (dtt == null) dtt = new DttRad2(dtt_size);
		for (int quad = 0; quad < 4; quad ++){
			kernels[quad] = dtt.dttt_iiie(kernels[quad], quad, dtt_size);
		}
 	}
/*	
	void clt_fill_coord_corr ( // add 6 more items to extra data:  dxc/dx,dyc/dy, dyc/dx, dyc/dy - pixel shift when applied to different center
			// and x0, y0 (which censor pixel this kernel applies to) ? - not needed
			double [][]   kernels, // set of 4 SS, AS, SA, AA kdernels, each dtt_size * dtt_size (may have 5-th with center shift
			
			)
	
*/
	public class CltExtra{
		public double data_x   = 0.0; // kernel data is relative to this displacement X (0.5 pixel increments)
		public double data_y   = 0.0; // kernel data is relative to this displacement Y (0.5 pixel increments)
		public double center_x = 0.0; // actual center X (use to find derivatives)
		public double center_y = 0.0; // actual center X (use to find derivatives)
		public double dxc_dx   = 0.0; // add this to data_x per each pixel X-shift relative to the kernel centger location
		public double dxc_dy   = 0.0; // same per each Y-shift pixel
		public double dyc_dx   = 0.0;		
		public double dyc_dy   = 0.0;		
		
		public CltExtra(){}
		public CltExtra(double [] data)
		{
			data_x   = data[0]; // kernel data is relative to this displacement X (0.5 pixel increments)
			data_y   = data[1]; // kernel data is relative to this displacement Y (0.5 pixel increments)
			center_x = data[2]; // actual center X (use to find derivatives)
			center_y = data[3]; // actual center X (use to find derivatives)
			dxc_dx   = data[4]; // add this to data_x per each pixel X-shift relative to the kernel centger location
			dxc_dy   = data[5]; // same per each Y-shift pixel
			dyc_dx   = data[6];		
			dyc_dy   = data[7];		
		}
		public double [] getArray()
		{
			double [] rslt = {
					data_x,
					data_y,
					center_x,
					center_y,
					dxc_dx,
					dxc_dy,
					dyc_dx,		
					dyc_dy		
			};
			return rslt;
		}
	}
	
	public void clt_fill_coord_corr(
			final int               kern_step, // distance between kernel centers, in pixels.
			final double [][][][][] clt_data,
			final int               threadsMax,     // maximal number of threads to launch                         
			final int               globalDebugLevel)
	{
		final int nChn=clt_data.length;
		final int tilesY=clt_data[0].length;
		final int tilesX=clt_data[0][0].length;
		final int nTilesInChn=tilesX*tilesY;
		final int nTiles=nTilesInChn*nChn;
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX,chn;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						chn=nTile/nTilesInChn;
						tileY =(nTile % nTilesInChn)/tilesX;
						tileX = nTile % tilesX;
						double s0=0.0, sx=0.0, sx2= 0.0, sy=0.0, sy2= 0.0, sz=0.0, sxz=0.0,
								syz=0.0, sw=0.0, sxw=0.0, syw=0.0;
						for (int dty = -1; dty < 2; dty++){
							int ty = tileY+dty;
							if ((ty >= 0) && (ty < tilesY)){
								for (int dtx = -1; dtx < 2; dtx++){
									int tx = tileX + dtx;
									if ((tx >= 0) && (tx < tilesX)){
										CltExtra ce = new CltExtra (clt_data[chn][ty][tx][4]);
										s0 +=  1;
										sx +=  dtx;
										sx2 += dtx*dtx;
										sy +=  dty;
										sy2 += dty*dty;
										sz  += ce.center_x;
										sxz += dtx * ce.center_x;
										syz += dty * ce.center_x;
										sw  += ce.center_y;
										sxw += dtx * ce.center_y;
										syw += dty * ce.center_y;
									}									
								}
							}
						}
						CltExtra ce = new CltExtra (clt_data[chn][tileY][tileX][4]);
						double denom_x = (sx2*s0-sx*sx)*kern_step;
						double denom_y = (sy2*s0-sy*sy)*kern_step;
						ce.dxc_dx= (sxz*s0 - sz*sx)/denom_x;
						ce.dxc_dy= (syz*s0 - sz*sy)/denom_y;
						ce.dyc_dx= (sxw*s0 - sw*sx)/denom_x;
						ce.dyc_dy= (syw*s0 - sw*sy)/denom_y;
						clt_data[chn][tileY][tileX][4] = ce.getArray();
					}
				}
			};
		}		      
		startAndJoin(threads);
	}

	public class CltTile{
		public double [][] tile = new double[4][]; // 4 CLT tiles
		public double fract_x; // remaining fractional offset X
		public double fract_y; // remaining fractional offset X
	}
	
	

// Extract and correct one image tile using kernel data, required result tile and shifts - x and y
// option - align to Bayer (integer shift by even pixels - no need
// input - RBG stack of sparse data
// return
// kernel [0][0] is centered at  (-kernel_step/2,-kernel_step/2)	
	
	public double [] extract_correct_tile( // return a pair of resudual offsets
			double [][]         image_data,
			int                 width,       // image width
			double  [][][][][]  clt_kernels, // [color][tileY][tileX][band][pixel]
			double  [][]        clt_tile,    // should be double [4][];
			int                 kernel_step,
			int                 transform_size,
			DttRad2             dtt, 
			int                 chn,                              
			double              centerX, // center of aberration-corrected (common model) tile, X
			double              centerY, // 
			boolean             bdebug0, // external tile compare
			boolean             dbg_no_deconvolution,
			boolean             dbg_transpose)
	{
		boolean bdebug = false;
		double [] residual_shift = new double[2];
		int height = image_data[0].length/width;
		int transform_size2 = 2* transform_size;
//		if (dtt == null) dtt = new DttRad2(transform_size); should have window set up
		double []   tile_in =  new double [4*transform_size*transform_size];    
		// 1. find closest kernel
		int ktileX = (int) Math.round(centerX/kernel_step) + 1;
		int ktileY = (int) Math.round(centerY/kernel_step) + 1;
		if      (ktileY < 0)                                ktileY = 0;
		else if (ktileY >= clt_kernels[chn].length)         ktileY = clt_kernels[chn].length-1;
		if      (ktileX < 0)                                ktileX = 0;
		else if (ktileX >= clt_kernels[chn][ktileY].length) ktileX = clt_kernels[chn][ktileY].length-1;
		CltExtra ce = new CltExtra (clt_kernels[chn][ktileY][ktileX][4]);
		// 2. calculate correction for center of the kernel offset
		double kdx = centerX - (ktileX -1 +0.5) *  kernel_step; // difference in pixel
		double kdy = centerY - (ktileY -1 +0.5) *  kernel_step;
		// 3. find top-left corner of the
		// check signs, ce.data_x is "-" as if original kernel was shifted to "+" need to take pixel sifted "-"
		// same with extra shift
		double px = centerX - transform_size - (ce.data_x + ce.dxc_dx * kdx + ce.dxc_dy * kdy) ; // fractional left corner
		double py = centerY - transform_size - (ce.data_y + ce.dyc_dx * kdx + ce.dyc_dy * kdy) ; // fractional top corner
		
		if (bdebug0){
			System.out.print(px+"\t"+py+"\t");
		}
		
		int ctile_left = (int) Math.round(px);
		int ctile_top =  (int) Math.round(py);
		residual_shift[0] = -(px - ctile_left);
		residual_shift[1] = -(py - ctile_top);
		// 4. Verify the tile fits in image and use System.arraycopy(sym_conv, 0, tile_in, 0, n2*n2) to copy data to tile_in
		// if does not fit - extend by duplication? Or just use 0?
		if ((ctile_left >= 0) && (ctile_left < (width - transform_size2)) &&
				(ctile_top >= 0) && (ctile_top < (height - transform_size2))) {
			for (int i = 0; i < transform_size2; i++){
				System.arraycopy(image_data[chn], (ctile_top + i) * width + ctile_left, tile_in, transform_size2 * i, transform_size2);
			}
		} else { // copy by 1
			for (int i = 0; i < transform_size2; i++){
				int pi = ctile_top + i;
				if      (pi < 0)       pi = 0;
				else if (pi >= height) pi = height - 1;
				for (int j = 0; j < transform_size2; j++){
					int pj = ctile_left + j;
					if      (pj < 0)      pj = 0;
					else if (pj >= width) pj = width - 1;
					tile_in[transform_size2 * i + j] = image_data[chn][pi * width + pj];
				}			
			}			
		}
		// Fold and transform
		double [][][] fold_coeff = null;
		if (!dbg_transpose){
			fold_coeff = dtt.get_shifted_fold_2d(
					transform_size,
					residual_shift[0],
					residual_shift[1]);
		}
		
		for (int dct_mode = 0; dct_mode <4; dct_mode++) {
			if (fold_coeff != null){
				clt_tile[dct_mode] = dtt.fold_tile (tile_in, transform_size, dct_mode, fold_coeff); // DCCT, DSCT, DCST, DSST
			} else {
				clt_tile[dct_mode] = dtt.fold_tile (tile_in, transform_size, dct_mode); // DCCT, DSCT, DCST, DSST
			}
			clt_tile[dct_mode] = dtt.dttt_iv   (clt_tile[dct_mode], dct_mode, transform_size);
		}
		if (bdebug) {
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			sdfa_instance.showArrays(tile_in,  transform_size2, transform_size2, "tile_in_x"+ctile_left+"_y"+ctile_top);
			String [] titles = {"CC","SC","CS","SS"};
			sdfa_instance.showArrays(clt_tile,  transform_size, transform_size, true, "clt_x"+ctile_left+"_y"+ctile_top, titles);
		}
		// deconvolve with kernel
		if (!dbg_no_deconvolution) {
			double [][] ktile = clt_kernels[chn][ktileY][ktileX];
			convolve_tile(
					clt_tile,        // double [][]     data,    // array [transform_size*transform_size], will be updated  DTT4 converted
					ktile,           // double [][]     kernel,  // array [4][transform_size*transform_size]  DTT3 converted
					transform_size,
					bdebug);
//					dbg_transpose);			
		}
		if (bdebug) {
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			String [] titles = {"CC","SC","CS","SS"};
			sdfa_instance.showArrays(clt_tile,  transform_size, transform_size, true, "acorr_x"+ctile_left+"_y"+ctile_top, titles);
		}
		return residual_shift;
	}
	
//	public 
	public void convolve_tile(
			double [][]     data,    // array [transform_size*transform_size], will be updated  DTT4 converted
			double [][]     kernel,  // array [4][transform_size*transform_size]  DTT3 converted
			int             transform_size,
			boolean         bdebug) // externally decoded debug tile
//			boolean         dbg_transpose)
			
	{
		/* Direct matrix Z1: X2 ~= Z1 * Shift   
		 * {{+cc  -sc  -cs  +ss},
		 *  {+sc  +cc  -ss  -cs},
		 *  {+cs  -ss  +cc  -sc},
		 *  {+ss  +cs  +sc  +cc}}
		 *  
		 * T= transp({cc, sc, cs, ss}) 
		 */
		/*
		final int [][] zi = 
			{{ 0, -1, -2,  3},
			 { 1,  0, -3, -2},
			 { 2, -3,  0, -1},
			 { 3,  2,  1,  0}};
		final int [][] zi = 
			{{ 0,  1,  2,  3},
			 {-1,  0, -3,  2},
			 {-2, -3,  0,  1},
			 { 3, -2, -1,  0}};
		 */
		// opposite sign from correlation		
		final int [][] zi =	{ // 
				{ 0, -1, -2,  3},
				{ 1,  0, -3, -2},
				{ 2, -3,  0, -1},
				{ 3,  2,  1,  0}};
		
		final int transform_len = transform_size * transform_size;
		final double [][] rslt = new double[4][transform_len];
		for (int i = 0; i < transform_len; i++) {
			for (int n = 0; n<4; n++){
				rslt[n][i] = 0;
				for (int k=0; k<4; k++){
					if (zi[n][k] < 0)
						rslt[n][i] -= data[-zi[n][k]][i] * kernel[k][i];
					else
						rslt[n][i] += data[ zi[n][k]][i] * kernel[k][i];
				}
			}
		}
		if (bdebug) {
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			String [] titles = {"CC","SC","CS","SS"};
			double [][] dbg_kern = {kernel[0],kernel[1],kernel[2],kernel[3]};
			sdfa_instance.showArrays(data,     transform_size, transform_size, true, "image_data", titles);
			sdfa_instance.showArrays(dbg_kern, transform_size, transform_size, true, "kernel",     titles);
			sdfa_instance.showArrays(rslt,     transform_size, transform_size, true, "aber_corr",  titles);
		}
		for (int n = 0; n<4; n++){
			data[n] = rslt[n];
		}
	}
	
	public void fract_shift(    // fractional shift in transform domain. Currently uses sin/cos - change to tables with 2? rotations
		double  [][]  clt_tile,
		int           transform_size,
		double        shiftX,
		double        shiftY,
		boolean       bdebug)
	{
		int transform_len = transform_size*transform_size;
		double [] cos_hor =  new double [transform_len];
		double [] sin_hor =  new double [transform_len];
		double [] cos_vert = new double [transform_len];
		double [] sin_vert = new double [transform_len];
		for (int i = 0; i < transform_size; i++){
			double ch = Math.cos((i+0.5)*Math.PI*shiftX/transform_size);
			double sh = Math.sin((i+0.5)*Math.PI*shiftX/transform_size);
			double cv = Math.cos((i+0.5)*Math.PI*shiftY/transform_size);
			double sv = Math.sin((i+0.5)*Math.PI*shiftY/transform_size);
			for (int j = 0; j < transform_size; j++){
				int iv = transform_size * j + i; // 2d DTT results are stored transposed! 
				int ih = transform_size * i + j; 
				cos_hor[ih] = ch; 
				sin_hor[ih] = sh; 
				cos_vert[iv] = cv; 
				sin_vert[iv] = sv; 
			}
		}
		if (bdebug){
			showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
			String [] titles = {"cos_hor","sin_hor","cos_vert","sin_vert"};
			double [][] cs_dbg = {cos_hor, sin_hor, cos_vert, sin_vert};
			sdfa_instance.showArrays(cs_dbg,  transform_size, transform_size, true, "shift_cos_sin", titles);
		}
		double [][] tmp_tile = new double [4][transform_len];
		// Horizontal shift CLT tiled data is stored in transposed way (horizontal - Y, vertical X) 
		for (int i = 0; i < cos_hor.length; i++) {
			tmp_tile[0][i] = clt_tile[0][i] * cos_hor[i] - clt_tile[1][i] * sin_hor[i];
			tmp_tile[1][i] = clt_tile[1][i] * cos_hor[i] + clt_tile[0][i] * sin_hor[i] ;

			tmp_tile[2][i] = clt_tile[2][i] * cos_hor[i]  - clt_tile[3][i] * sin_hor[i];
			tmp_tile[3][i] = clt_tile[3][i] * cos_hor[i]  + clt_tile[2][i] * sin_hor[i] ;
		}
		// Vertical shift (back to original array)
		for (int i = 0; i < cos_hor.length; i++) {
			clt_tile[0][i] = tmp_tile[0][i] * cos_vert[i] - tmp_tile[2][i] * sin_vert[i];
			clt_tile[2][i] = tmp_tile[2][i] * cos_vert[i] + tmp_tile[0][i] * sin_vert[i];

			clt_tile[1][i] =                      tmp_tile[1][i] * cos_vert[i] - tmp_tile[3][i] * sin_vert[i];
			clt_tile[3][i] = tmp_tile[3][i] * cos_vert[i] + tmp_tile[1][i] * sin_vert[i];
		}
	}
	
	
	
	public double [][][][] mdctScale(
			final ImageStack                                 imageStack,
			final int                                        subcamera, // not needed
			final EyesisCorrectionParameters.DCTParameters   dctParameters, //
			final int                                        threadsMax, // maximal step in pixels on the maxRadius for 1 angular step (i.e. 0.5)
			final int                                        debugLevel,
			final boolean                                    updateStatus) // update status info

	{
	  	  if (imageStack==null) return null;
		  final int imgWidth=imageStack.getWidth();
		  final int nChn=imageStack.getSize();
		  double [][][][] dct_data = new double [nChn][][][];
		  float [] fpixels;
		  int i,chn; //tileX,tileY;
		  /* find number of the green channel - should be called "green", if none - use last */
		  // Extract float pixels from inage stack, convert each to double

		  
		  for (chn=0;chn<nChn;chn++) {
			  fpixels= (float[]) imageStack.getPixels(chn+1);
			  double[] dpixels = new double[fpixels.length];
			  for (i = 0; i <fpixels.length;i++) dpixels[i] = fpixels[i];
			  // convert each to DCT tiles
			  dct_data[chn] =lapped_dct_scale(
						dpixels,
						imgWidth,
						dctParameters.dct_size,
						(int) Math.round(dctParameters.dbg_src_size),
						dctParameters.dbg_fold_scale,
						dctParameters.dbg_fold_scale,
						0, //     dct_mode,    // 0: dct/dct, 1: dct/dst, 2: dst/dct, 3: dst/dst
						dctParameters.dct_window, // final int       window_type,
						chn,
						dctParameters.tileX,
						dctParameters.tileY,
						dctParameters.dbg_mode,
						threadsMax,  // maximal number of threads to launch                         
						debugLevel);
		  }
		return dct_data;
	}
	
	
	
	public double [][][] lapped_dct_scale( // scale image to 8/9 size in each direction
			final double [] dpixels,
			final int       width,
			final int       dct_size,
			final int       src_size,    // source step (== dct_size - no scale, == 9 - shrink, ==7 - expand
			final double    scale_hor,
			final double    scale_vert,
			final int       dct_mode,    // 0: dct/dct, 1: dct/dst, 2: dst/dct, 3: dst/dst
			final int       window_type,
			final int       color,
			final int       debug_tileX,
			final int       debug_tileY,
			final int       debug_mode,
			final int       threadsMax,  // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
		final int height=dpixels.length/width;
		final int n2 = dct_size * 2;

		final int tilesX = (width - n2) / src_size + 1;
		final int tilesY = (height - n2) / src_size + 1;

		final int nTiles=tilesX*tilesY; 
		final double [][][] dct_data = new double[tilesY][tilesX][dct_size*dct_size];
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);
		for (int tileY = 0; tileY < tilesY; tileY++){
			for (int tileX = 0; tileX < tilesX; tileX++){
				for (int i=0; i<dct_data[tileY][tileX].length;i++) dct_data[tileY][tileX][i]= 0.0; // actually not needed, Java initializes arrays
			}
		}
		double [] dc = new double [dct_size*dct_size];
		for (int i = 0; i<dc.length; i++) dc[i] = 1.0;
//		DttRad2 dtt0 = new DttRad2(dct_size);
//		dtt0.set_window(window_type);
//		final double [] dciii = dtt0.dttt_iii  (dc, dct_size);
//		final double [] dciiie = dtt0.dttt_iiie  (dc, 0, dct_size);

		
		if (globalDebugLevel > 0) {
			System.out.println("lapped_dctdc(): width="+width+" height="+height);
		}

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					DttRad2 dtt = new DttRad2(dct_size);
					dtt.set_window(window_type);
					double [] tile_in = new double[4*dct_size * dct_size];
					double [] tile_folded;
					double [] tile_out; // = new double[dct_size * dct_size];
					int tileY,tileX;
					double [][][] fold_k =  dtt.get_fold_2d(
//							int n,
							scale_hor,
							scale_vert
							);
//					double [] tile_out_copy = null;
//					showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						//readDCTKernels() debugLevel = 1 kernels[0].size = 8 kernels[0].img_step = 16 kernels[0].asym_nonzero = 4 nColors = 3 numVert = 123 numHor =  164
						// no aberration correction, just copy data
						for (int i = 0; i < n2;i++){
							System.arraycopy(dpixels, (tileY*width+tileX)*src_size + i*width, tile_in, i*n2, n2);
						}
						tile_folded=dtt.fold_tile(tile_in, dct_size, 0, fold_k); // DCCT
						tile_out=dtt.dttt_iv  (tile_folded, dct_mode, dct_size);

//						if ((tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
//							tile_out_copy = tile_out.clone();
//						}
						
						System.arraycopy(tile_out, 0, dct_data[tileY][tileX], 0, tile_out.length);
					}
				}
			};
		}		      
		startAndJoin(threads);
		return dct_data;
	}
	
	public void dct_scale(
			final double  scale_hor,  // < 1.0 - enlarge in dct domain (shrink in time/space)
			final double  scale_vert, // < 1.0 - enlarge in dct domain (shrink in time/space)
			final boolean normalize, // preserve weighted dct values
			final double [][][] dct_data,
			final int       debug_tileX,
			final int       debug_tileY,
			final int       threadsMax,     // maximal number of threads to launch                         
			final int       globalDebugLevel)
	{
		final int tilesY=dct_data.length;
		final int tilesX=dct_data[0].length;
		final int nTiles=tilesX*tilesY;
		final int dct_size = (int) Math.round(Math.sqrt(dct_data[0][0].length));
		final int dct_len = dct_size*dct_size;
		final double [] norm_sym_weights = new double [dct_size*dct_size];
		for (int i = 0; i < dct_size; i++){
			for (int j = 0; j < dct_size; j++){
				double d = 	Math.cos(Math.PI*i/(2*dct_size))*Math.cos(Math.PI*j/(2*dct_size));
				if (i > 0) d*= 2.0;
				if (j > 0) d*= 2.0;
				norm_sym_weights[i*dct_size+j] = d;
			}
		}
		final Thread[] threads = newThreadArray(threadsMax);
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() {
				public void run() {
					int tileY,tileX;
					double [] dct1 = new double [dct_size*dct_size];
					double [] dct;
					double [][] bidata = new double [2][2];
					int dct_m1 = dct_size - 1;
					int dct_m2 = dct_size - 2;
					for (int nTile = ai.getAndIncrement(); nTile < nTiles; nTile = ai.getAndIncrement()) {
						tileY = nTile/tilesX;
						tileX = nTile - tileY * tilesX;
						dct = dct_data[tileY][tileX];
						double sum_orig=0;
						if (normalize) {
							for (int i = 0; i < dct_len; i++){
								sum_orig += dct[i] *norm_sym_weights[i];
							}
						}
						for (int i = 0; i < dct_size; i++){
							double fi = i * scale_vert;
							int i0 = (int) fi;
							fi -= i0;
							for (int j = 0; j < dct_size; j++){
								double fj = j * scale_hor;
								int j0 = (int) fj;
								fj -= j0;
								int indx = i0*dct_size+j0;
								if ((i0 > dct_m1) || (j0 > dct_m1)){
									bidata[0][0] = 0.0;
									bidata[0][1] = 0.0;
									bidata[1][0] = 0.0;
									bidata[1][1] = 0.0;
								} else {
									bidata[0][0] = dct[indx];
									if (i0 > dct_m2) {
										bidata[1][0] = 0.0;
										bidata[1][1] = 0.0;
										if (j0 > dct_m2) {
											bidata[0][1] = 0.0;
										} else {
											bidata[0][1] = dct[indx + 1];
										}
									} else {
										bidata[1][0] = dct[indx+dct_size];
										if (j0 > dct_m2) {
											bidata[0][1] = 0.0;
											bidata[1][1] = 0.0;
										} else {
											bidata[0][1] = dct[indx + 1];
											bidata[1][1] = dct[indx + dct_size + 1];
										}
									}
									
								}
								// bilinear interpolation 
								dct1[i*dct_size+j] =
										bidata[0][0] * (1.0-fi) * (1.0-fj) +
										bidata[0][1] * (1.0-fi) *      fj  +
										bidata[1][0] *      fi  * (1.0-fj) +
										bidata[1][1] *      fi *       fj;
								if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX)) {
									System.out.println(i+":"+j+" {"+bidata[0][0]+","+bidata[0][1]+","+bidata[1][0]+","+bidata[1][1]+"}, ["+fi+","+fj+"] "+bidata[1][1]);
								}
							}
						}
						if (normalize) {
							double sum=0;
							for (int i = 0; i < dct_len; i++){
								sum += dct1[i] *norm_sym_weights[i];
							}
							if (sum >0.0) {
								double k = sum_orig/sum;
								for (int i = 0; i < dct_len; i++){
									dct1[i] *= k;
								}
							}
						}
//						if ((tileY == debug_tileY) && (tileX == debug_tileX) && (color == 2)) {
						if ((globalDebugLevel > 0) && (tileY == debug_tileY) && (tileX == debug_tileX)) {
							double [][] scaled_tiles = {dct, dct1};
							showDoubleFloatArrays sdfa_instance = new showDoubleFloatArrays(); // just for debugging?
							String [] titles = {"orig","scaled"};
							sdfa_instance.showArrays(scaled_tiles,  dct_size, dct_size, true, "scaled_tile", titles);
						}
						
						System.arraycopy(dct1, 0, dct, 0, dct_len); // replace original data
					}
				}
			};
		}		      
		startAndJoin(threads);
	}
	
	
	
	
	
	/* Create a Thread[] array as large as the number of processors available.
	 * From Stephan Preibisch's Multithreading.java class. See:
	 * http://repo.or.cz/w/trakem2.git?a=blob;f=mpi/fruitfly/general/MultiThreading.java;hb=HEAD
	 */
	static Thread[] newThreadArray(int maxCPUs) {
		int n_cpus = Runtime.getRuntime().availableProcessors();
		if (n_cpus>maxCPUs)n_cpus=maxCPUs;
		return new Thread[n_cpus];
	}
/* Start all given threads and wait on each of them until all are done.
	 * From Stephan Preibisch's Multithreading.java class. See:
	 * http://repo.or.cz/w/trakem2.git?a=blob;f=mpi/fruitfly/general/MultiThreading.java;hb=HEAD
	 */
	public static void startAndJoin(Thread[] threads)
	{
		for (int ithread = 0; ithread < threads.length; ++ithread)
		{
			threads[ithread].setPriority(Thread.NORM_PRIORITY);
			threads[ithread].start();
		}

		try
		{   
			for (int ithread = 0; ithread < threads.length; ++ithread)
				threads[ithread].join();
		} catch (InterruptedException ie)
		{
			throw new RuntimeException(ie);
		}
	}
	
}