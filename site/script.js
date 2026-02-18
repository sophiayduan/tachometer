// Below is for the graph of the magnetic field strength (it updates live)
const ctx = document.getElementById('graph').getContext('2d');
const graph = new Chart (ctx, {
    type: 'line',
    data: {
        labels: [], // Time labels
        datasets: [{
            label: 'Magnetic Field (mT)',
            data: [], // Magnetic field data points
            borderColor: '#EBEBEB',
            borderWidth: 1, // TODO
            backgroundColor: 'rgba(60, 199, 88, 0.80)', // #3CC758 but at 80% opacity
            fill: true,
            pointRadius: 0, // Hide points -> smooth line
        }]
    },
    
    options: {
        animation: false,
        responsive:true,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                labels: { color: '#EBEBEB' } // Title colour
            }
        },
        scales: {
        x: { 
            title: { display: true, text: 'Time', color: '#EBEBEB' },                
        },
        y: { 
            title: { display: true, text: 'Magnetic Field Strength (mT)', color: '#EBEBEB' },
            ticks: { color: '#EBEBEB' },
            grid: { color: 'rgba(235, 235, 235, 0.40)' }, // #EBEBEB but at 60% opacity
            beginAtZero: false 
        }
    }
        
    }
});

const displayPoints = 50; // Max number of points to display on the graph
let time = 0; 

const socket = new WebSocket("ws://localhost:8081"); // Connect to port 8081 (HTTP)
const magField = document.getElementById("mag"); // Magnetic field strength (mT)
const rpm = document.getElementById("rpm"); // RPM (determined from peaks in the magField)
const kV = document.getElementById("kv"); // kV rating (RPM / Voltage)

socket.onopen = () => {
    magField.textContent = "Connected...";
    kV.textContent = "Connected...";


};
socket.onmessage = (event) => {
    let mag;
    let data = {}; // For the graph (only needs magnetic field strength)
    
    try {
        // Parses the JSON msg for each value (magnetic field, rpm, kV)
        data = JSON.parse(event.data);
        mag = data.magneticField;
    } catch { 
        const match = event.data.match(/([\d.]+)/);
        mag = match ? parseFloat(match[1]) : null;
    }


    if (data.magneticField !== undefined)
        magField.textContent = `${data.magneticField.toFixed(2)} mT`;
    if (data.rpm !== undefined)                   
        rpm.textContent = `${Math.round(data.rpm)} RPM`;
    if (data.kV !== undefined) 
        kV.textContent = `${Math.round(data.kV)} kV`;
    

    if(mag !== null && mag !== undefined) {
        graph.data.labels.push(time++); // Increment time by 1
        graph.data.datasets[0].data.push(mag); // Add new magnetic field data point\

        // Below ensures that only the a certain amount of points are displayed (based on displayPoints)
        if (graph.data.labels.length > displayPoints) {
            graph.data.labels.shift(); // Remove oldest time label
            graph.data.datasets[0].data.shift(); // Remove oldest data point
        }
        graph.update(); 
    }
};

socket.onclose = () => {
    magField.textContent = "Disconnected.";
};
// Error 
socket.onerror = (error) => {
    magField.textContent = "Error";
};
