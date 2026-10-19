import { WahanaPlugin } from 'capacitor-plugingps-wahana';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    WahanaPlugin.echo({ value: inputValue })
}
