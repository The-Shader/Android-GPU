#version 310 es

layout(rgba8, binding = 0) uniform readonly image2D inTexture;

layout(rgba8, binding = 1) uniform writeonly image2D outTexture;

layout(location = 2) uniform ivec2 alignmentMin;

layout(location = 3) uniform ivec2 alignmentMax;

layout (local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
void main()
{
      ivec2 storePos = ivec2(gl_GlobalInvocationID.xy);

      vec4 texColor = imageLoad(inTexture,storePos).rgba;

      if(storePos.x > alignmentMin.x && storePos.x < alignmentMax.x) {
            if(storePos.y > alignmentMin.y && storePos.y < alignmentMax.y) {
                  float newPixel = .299f * texColor.r + .587f * texColor.g + .114f * texColor.b;

                  imageStore(outTexture, storePos, vec4(newPixel, newPixel, newPixel, texColor.a));

            } else {
                  imageStore(outTexture, storePos, texColor);
            }
      } else {
            imageStore(outTexture, storePos, texColor);
      }
}