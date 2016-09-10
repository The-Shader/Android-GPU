#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES sTexture;
uniform sampler2D inTexture;
uniform int isOffScreen;
varying vec2 texCoord;

void main()
{

    if(isOffScreen == 1) {
        vec4 texColor = texture2D(sTexture, texCoord);
        gl_FragColor = texColor;
    } else {
        vec4 texColor = texture2D(inTexture, texCoord);
        gl_FragColor = texColor;
    }
}
