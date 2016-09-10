precision mediump float;
uniform sampler2D sTexture;
varying vec2 texCoord;

void main()
{
    vec4 texColor = texture2D(sTexture,texCoord);

    gl_FragColor = texColor;
}