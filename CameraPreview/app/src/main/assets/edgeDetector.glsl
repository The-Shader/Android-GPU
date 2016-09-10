#version 310 es

layout(rgba8, binding = 1) uniform readonly image2D inTexture;

layout(rgba8, binding = 2) uniform writeonly image2D outTexture;

layout(location = 3) uniform ivec2 alignmentMin;

layout(location = 4) uniform ivec2 alignmentMax;

float luma(vec3 color)
{
    return 0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b;
}

layout (local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
void main()
{
    ivec2 storePos = ivec2(gl_GlobalInvocationID.xy);
    vec2 pos = vec2(gl_GlobalInvocationID.xy);

    uint gWidth = gl_WorkGroupSize.x * gl_NumWorkGroups.x;
    uint gHeight = gl_WorkGroupSize.y * gl_NumWorkGroups.y;

    vec4 texColor = imageLoad(inTexture, storePos).rgba;

    float threshold = 5.0f/66.0f;

    if(storePos.x > alignmentMin.x && storePos.x < alignmentMax.x) {
          if(storePos.y > alignmentMin.y && storePos.y < alignmentMax.y) {

                //Applying Sobel operator
                float s00 = luma(imageLoad(inTexture, storePos + ivec2(-1,1)).rgb);
                float s10 = luma(imageLoad(inTexture, storePos + ivec2(-1,0)).rgb);
                float s20 = luma(imageLoad(inTexture, storePos + ivec2(-1,-1)).rgb);
                float s01 = luma(imageLoad(inTexture, storePos + ivec2(0.,1)).rgb);
                float s21 = luma(imageLoad(inTexture, storePos + ivec2(0,-1)).rgb);
                float s02 = luma(imageLoad(inTexture, storePos + ivec2(1, 1)).rgb);
                float s12 = luma(imageLoad(inTexture, storePos + ivec2(1,0)).rgb);
                float s22 = luma(imageLoad(inTexture, storePos + ivec2(1, -1)).rgb);
                float sx = s00 + 2.0f * s10 + s20 - (s02 + 2.0f * s12 + s22);
                float sy = s00 + 2.0f * s01 + s02 - (s20 + 2.0f * s21 + s22);
                float dist = sx * sx + sy * sy;

                if (dist > threshold) {
                    imageStore(outTexture, storePos, vec4(1.0f));
                } else {
                    imageStore(outTexture, storePos, vec4(0.0f, 0.0f, 0.0f, 1.0f));
                }

          } else {
                 imageStore(outTexture, storePos, texColor);
          }
    } else {
           imageStore(outTexture, storePos, texColor);
    }
}
