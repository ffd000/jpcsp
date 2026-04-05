/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.graphics.export;

import static jpcsp.graphics.GeCommands.PRIM_TRIANGLE;
import static jpcsp.graphics.GeCommands.PRIM_TRIANGLE_STRIPS;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.log4j.Logger;

import jpcsp.graphics.GeContext;
import jpcsp.graphics.VertexState;
import jpcsp.graphics.VideoEngine;

/**
 * @author gid15
 * Enhanced Wavefront OBJ Exporter with Bone Data Export
 * Exports bone hierarchy, transformation matrices, and vertex bone weights
 */
public class WavefrontExporter implements IGraphicsExporter {
	private static Logger log = VideoEngine.log;
	private static Locale l = Locale.ENGLISH;
	private GeContext context;
    private BufferedWriter exportObj;
    private BufferedWriter exportMtl;
    private BufferedWriter exportBones;
    private int exportVertexCount;
    private int exportTextureCount;
    private int exportNormalCount;
    private int exportModelCount;
    private int exportMaterialCount;
    private int exportBoneCount;
    private static final boolean exportNormal = false;
    
    // Bone tracking data structures
    private List<BoneData> boneHierarchy = new ArrayList<>();
    private Map<Integer, VertexBoneData> vertexBoneData = new HashMap<>();
    private int vertexCounter = 0;
    
    /**
     * Inner class to track bone information per vertex
     */
    private static class VertexBoneData {
        int vertexId;
        float[] weights = new float[8];
        int[] boneIndices = new int[8];
        int boneCount;
        
        VertexBoneData(int vertexId) {
            this.vertexId = vertexId;
            this.boneCount = 0;
        }
    }
    
    /**
     * Inner class to represent bone hierarchy and transformation data
     */
    private static class BoneData {
        String name;
        int boneIndex;
        int parentIndex;
        float[] transform = new float[16]; // 4x4 matrix (row-major)
        
        BoneData(int index, String name) {
            this.boneIndex = index;
            this.name = name != null ? name : String.format("Bone_%d", index);
            this.parentIndex = -1;
            // Initialize to identity matrix
            for (int i = 0; i < 16; i++) {
                transform[i] = (i % 5 == 0) ? 1.0f : 0.0f;
            }
        }
    }

    protected void exportObjLine(String line) {
        if (exportObj != null) {
            try {
                exportObj.write(line);
                exportObj.newLine();
            } catch (IOException e) {
                log.error("Error writing export.obj file", e);
            }
        }
    }

    protected void exportMtlLine(String line) {
        if (exportMtl != null) {
            try {
                exportMtl.write(line);
                exportMtl.newLine();
            } catch (IOException e) {
                log.error("Error writing export.mtl file", e);
            }
        }
    }
    
    protected void exportBonesLine(String line) {
        if (exportBones != null) {
            try {
                exportBones.write(line);
                exportBones.newLine();
            } catch (IOException e) {
                log.error("Error writing export.bones file", e);
            }
        }
    }

    public static String getExportDirectory() {
        for (int i = 1; true; i++) {
            String directory = String.format("%sExport-%d%c", IGraphicsExporter.exportDirectory, i, File.separatorChar);
            if (!new File(directory).exists()) {
                return directory;
            }
        }
    }

    @Override
    public void startExport(GeContext context, String directory) {
        this.context = context;

        try {
            // Prepare the export writers
            exportObj = new BufferedWriter(new FileWriter(String.format("%sexport.obj", directory)));
            exportMtl = new BufferedWriter(new FileWriter(String.format("%sexport.mtl", directory)));
            exportBones = new BufferedWriter(new FileWriter(String.format("%sexport.bones", directory)));
        } catch (IOException e) {
            log.error("Error creating the export files", e);
        }
        exportVertexCount = 1;
        exportModelCount = 1;
        exportTextureCount = 1;
        exportMaterialCount = 1;
        exportBoneCount = 0;
        vertexCounter = 0;

        exportObjLine(String.format("mtllib export.mtl"));
        
        // Write bones file header
        exportBonesLine("# Bone Data Export Format");
        exportBonesLine("# This file contains bone hierarchy, transformations, and vertex skinning data");
        exportBonesLine("# ");
        exportBonesLine("# Bone Definition:");
        exportBonesLine("#   bone <bone_id> <parent_id> <bone_name>");
        exportBonesLine("#   matrix <bone_id>");
        exportBonesLine("#   <4 rows of 4x4 transformation matrix>");
        exportBonesLine("# ");
        exportBonesLine("# Vertex Weights:");
        exportBonesLine("#   vertex <vertex_id>");
        exportBonesLine("#   weight <bone_id> <weight_value>");
        exportBonesLine("");
    }

    @Override
    public void endExport() {
        // Write complete bone hierarchy to bones file
        if (exportBones != null) {
            try {
                exportBonesLine("# ===== BONE HIERARCHY =====");
                for (BoneData bone : boneHierarchy) {
                    exportBonesLine(String.format("bone %d %d %s", bone.boneIndex, bone.parentIndex, bone.name));
                    
                    // Export bone transformation matrix (4x4)
                    exportBonesLine(String.format("matrix %d", bone.boneIndex));
                    for (int i = 0; i < 4; i++) {
                        exportBonesLine(String.format(l, "%f %f %f %f", 
                            bone.transform[i*4], bone.transform[i*4+1], 
                            bone.transform[i*4+2], bone.transform[i*4+3]));
                    }
                    exportBonesLine("");
                }
                
                exportBonesLine("# ===== VERTEX BONE WEIGHTS =====");
                exportBonesLine(String.format("# Total vertices with bone weights: %d", vertexBoneData.size()));
                exportBonesLine("");
                
                for (VertexBoneData vbd : vertexBoneData.values()) {
                    exportBonesLine(String.format("vertex %d", vbd.vertexId));
                    for (int i = 0; i < vbd.boneCount; i++) {
                        exportBonesLine(String.format(l, "  weight %d %f", vbd.boneIndices[i], vbd.weights[i]));
                    }
                }
                
                exportBonesLine("");
                exportBonesLine(String.format("# Export completed. Total bones: %d", exportBoneCount));
                
            } catch (Exception e) {
                log.error("Error writing bone hierarchy", e);
            }
        }
        
        if (exportObj != null) {
            try {
                exportObj.close();
            } catch (Exception e) {
                log.error("Error closing export.obj file", e);
            }
            exportObj = null;
        }

        if (exportMtl != null) {
            try {
                exportMtl.close();
            } catch (IOException e) {
                log.error("Error closing export.mtl file", e);
            }
            exportMtl = null;
        }
        
        if (exportBones != null) {
            try {
                exportBones.close();
            } catch (IOException e) {
                log.error("Error closing export.bones file", e);
            }
            exportBones = null;
        }
    }

    @Override
    public void startPrimitive(int numberOfVertex, int primitiveType) {
        if (log.isTraceEnabled()) {
            log.trace(String.format("Exporting Object model%d", exportModelCount));
        }

        exportObjLine(String.format("# modelCount=%d, vertexCount=%d, textureCount=%d, normalCount=%d", 
            exportModelCount, exportVertexCount, exportTextureCount, exportNormalCount));
    }

    @Override
    public void exportVertex(VertexState originalV, VertexState transformedV) {
        exportObjLine(String.format(l, "v %f %f %f", transformedV.p[0], transformedV.p[1], transformedV.p[2]));
        if (context.vinfo.texture != 0) {
            exportObjLine(String.format(l, "vt %f %f", transformedV.t[0], transformedV.t[1]));
        }
        if (exportNormal && context.vinfo.normal != 0) {
            exportObjLine(String.format(l, "vn %f %f %f", transformedV.n[0], transformedV.n[1], transformedV.n[2]));
        }
        
        // Track bone weights for this vertex
        if (originalV.boneWeights != null && originalV.boneWeights.length > 0) {
            VertexBoneData vbd = new VertexBoneData(vertexCounter);
            int nonZeroCount = 0;
            
            for (int i = 0; i < originalV.boneWeights.length && nonZeroCount < 8; i++) {
                if (originalV.boneWeights[i] > 0.001f) { // Threshold to avoid floating point noise
                    vbd.weights[nonZeroCount] = originalV.boneWeights[i];
                    vbd.boneIndices[nonZeroCount] = i;
                    nonZeroCount++;
                }
            }
            
            vbd.boneCount = nonZeroCount;
            if (nonZeroCount > 0) {
                vertexBoneData.put(vertexCounter, vbd);
            }
        }
        
        vertexCounter++;
    }

    @Override
    public void endVertex(int numberOfVertex, int primitiveType) {
        // Export object material
        exportObjLine(String.format("g model%d", exportModelCount));
        exportObjLine(String.format("usemtl material%d", exportMaterialCount));

        // Export faces
        exportObjLine("");
        switch (primitiveType) {
            case PRIM_TRIANGLE: {
                boolean clockwise = context.frontFaceCw;

                for (int i = 0; i < numberOfVertex; i += 3) {
                    if (clockwise) {
                        exportFace(i + 1, i, i + 2);
                    } else {
                        exportFace(i, i + 1, i + 2);
                    }
                }
                break;
            }
            case PRIM_TRIANGLE_STRIPS: {
                for (int i = 0; i < numberOfVertex - 2; i++) {
                    boolean clockwise = (i % 2) == 0;

                    if (!context.frontFaceCw) {
                        clockwise = !clockwise;
                    }

                    if (clockwise) {
                        exportFace(i + 1, i, i + 2);
                    } else {
                        exportFace(i, i + 1, i + 2);
                    }
                }
                break;
            }
        }
    }

    @Override
    public void endPrimitive(int numberOfVertex, int primitiveType) {
        exportVertexCount += numberOfVertex;
        if (context.vinfo.texture != 0) {
            exportTextureCount += numberOfVertex;
        }
        if (exportNormal && context.vinfo.normal != 0) {
            exportNormalCount += numberOfVertex;
        }
        exportModelCount++;
        exportMaterialCount++;
    }

    @Override
    public void exportTexture(String fileName) {
        int illum = 1;
        exportMtlLine(String.format("newmtl material%d", exportMaterialCount));
        exportMtlLine(String.format("illum %d", illum));

        exportColor(l, "Ka", context.mat_ambient);
        exportColor(l, "Kd", context.mat_diffuse);
        exportColor(l, "Ks", context.mat_specular);

        if (fileName != null) {
            exportMtlLine(String.format("map_Kd %s", fileName));
        }
    }
    
    /**
     * Add a bone to the export hierarchy
     * @param boneIndex Unique bone identifier
     * @param boneName Human-readable bone name
     * @param parentIndex Parent bone index (-1 for root bones)
     * @param transformMatrix 4x4 transformation matrix (row-major format)
     */
    public void addBone(int boneIndex, String boneName, int parentIndex, float[] transformMatrix) {
        BoneData bone = new BoneData(boneIndex, boneName);
        bone.parentIndex = parentIndex;
        if (transformMatrix != null && transformMatrix.length == 16) {
            System.arraycopy(transformMatrix, 0, bone.transform, 0, 16);
        }
        boneHierarchy.add(bone);
        exportBoneCount++;
        
        if (log.isDebugEnabled()) {
            log.debug(String.format("Added bone: id=%d, name=%s, parent=%d", boneIndex, boneName, parentIndex));
        }
    }

    private void exportFace(int i1, int i2, int i3) {
        int p1 = i1 + exportVertexCount;
        int p2 = i2 + exportVertexCount;
        int p3 = i3 + exportVertexCount;
        if (exportNormal && context.vinfo.normal != 0) {
            int n1 = i1 + exportNormalCount;
            int n2 = i2 + exportNormalCount;
            int n3 = i3 + exportNormalCount;
            if (context.vinfo.texture != 0) {
                int t1 = i1 + exportTextureCount;
                int t2 = i2 + exportTextureCount;
                int t3 = i3 + exportTextureCount;
                exportObjLine(String.format("f %d/%d/%d %d/%d/%d %d/%d/%d", p1, t1, n1, p2, t2, n2, p3, t3, n3));
            } else {
                exportObjLine(String.format("f %d//%d %d//%d %d//%d", p1, n1, p2, n2, p3, n3));
            }
        } else {
            if (context.vinfo.texture != 0) {
                int t1 = i1 + exportTextureCount;
                int t2 = i2 + exportTextureCount;
                int t3 = i3 + exportTextureCount;
                exportObjLine(String.format("f %d/%d %d/%d %d/%d", p1, t1, p2, t2, p3, t3));
            } else {
                exportObjLine(String.format("f %d %d %d", p1, p2, p3));
            }
        }
    }

    private void exportColor(Locale l, String name, float[] color) {
        exportMtlLine(String.format(l, "%s %f %f %f", name, color[0], color[1], color[2]));
    }
}
