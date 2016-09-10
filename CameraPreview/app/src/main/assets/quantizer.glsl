#version 310 es

layout(rgba8, binding = 1) uniform readonly image2D inTexture;

layout(rgba8, binding = 2) uniform writeonly image2D outTexture;

layout(location = 3) uniform ivec2 alignmentMin;

layout(location = 4) uniform ivec2 alignmentMax;

layout(std140, binding = 5) buffer Input1 {
      vec4 color[];
} centroids;

layout (local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

void main()
{
      ivec2 storePos = ivec2(gl_GlobalInvocationID.xy);

      const int dim = 3;

      const int numOfClusters = 32; //number of colors in the quantized image

      float distance, newDistance;

      uint gWidth = gl_WorkGroupSize.x * gl_NumWorkGroups.x;
      uint gHeight = gl_WorkGroupSize.y * gl_NumWorkGroups.y;

      int idx = int(storePos.y) * int(gWidth) + int(storePos.x);

      vec4 texColor = imageLoad(inTexture, storePos).rgba;

      if(idx < numOfClusters) {

            uint gSize = gWidth * gHeight;

            int interval = int(gSize) / numOfClusters;

            int ind = int(storePos.x) * interval + interval / 2;

            int yAxis = ind / int(gWidth);

            int xAxis = ind % int(gWidth);

            centroids.color[idx] = imageLoad(inTexture, ivec2( xAxis, yAxis)).rgba;
      }

      memoryBarrierBuffer();

      if(storePos.x > alignmentMin.x && storePos.x < alignmentMax.x) {

             if(storePos.y > alignmentMin.y && storePos.y < alignmentMax.y) {

                  int centroids_index = 0;

                  vec3 distanceVec = texColor.rgb - centroids.color[0].rgb;

                  distance = length(distanceVec);

                  for (int i = 1; i < numOfClusters; ++i) {

                        distanceVec = texColor.rgb - centroids.color[i].rgb;

                        newDistance = length(distanceVec);

                        if (newDistance < distance) {
                              centroids_index = i;
                              distance = newDistance;
                              }
                        }

                        imageStore(outTexture, storePos, centroids.color[centroids_index]);
            } else {
                  imageStore(outTexture, storePos, texColor);
            }
      } else {
            imageStore(outTexture, storePos, texColor);
      }
}